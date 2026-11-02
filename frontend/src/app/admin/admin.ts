import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, KeyValuePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import {
  ActivityEntry, AdminProject, AdminSettingsData, AdminUser, AdminView, ApiService, HealthInfo, problem,
} from '../api.service';

/** Bytes as KB, MB or GB for the size columns. */
export function size(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) {
    return '';
  }
  const units = ['bytes', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return (i === 0 ? v : v.toFixed(1)) + ' ' + units[i];
}

/** The details of an activity entry (everything but time, user, action and project) as one line. */
export function detail(e: ActivityEntry): string {
  return Object.entries(e)
    .filter(([k]) => !['time', 'user', 'action', 'project'].includes(k))
    .map(([k, v]) => k + '=' + (typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)))
    .join('; ');
}

/** The admin page: site settings, project defaults, users, every project, the activity log and health. */
@Component({
  selector: 'app-admin',
  imports: [DatePipe, FormsModule, KeyValuePipe, RouterLink],
  templateUrl: './admin.html',
})
export class AdminPage implements OnInit {
  private readonly api = inject(ApiService);

  readonly view = signal<AdminView | null>(null);
  readonly users = signal<AdminUser[]>([]);
  readonly projects = signal<AdminProject[]>([]);
  readonly activity = signal<ActivityEntry[]>([]);
  readonly health = signal<HealthInfo | null>(null);
  readonly error = signal<string | null>(null);
  readonly saved = signal<string | null>(null);
  readonly size = size;
  readonly detail = detail;

  /** A copy of the settings the form edits. */
  s: AdminSettingsData | null = null;
  roots = '';
  defaultsText = '';
  lockedText = '';
  newUser = '';
  newRole = 'owner';
  activityProject = '';

  ngOnInit(): void {
    this.api.admin().subscribe({ next: (v) => this.fill(v), error: (err: HttpErrorResponse) => this.error.set(problem(err)) });
    this.loadUsers();
    this.api.adminProjects().subscribe({ next: (p) => this.projects.set(p) });
    this.loadActivity();
    this.api.health().subscribe({ next: (h) => this.health.set(h) });
  }

  locked(key: string): boolean {
    return key in (this.view()?.env_locked ?? {});
  }

  save(): void {
    if (!this.s) {
      return;
    }
    const lines = (t: string) => t.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
    this.s.paths.extra_source_roots = lines(this.roots);
    this.s.locked = lines(this.lockedText);
    this.done(this.api.saveAdmin(this.s), 'Settings saved.');
  }

  saveDefaults(): void {
    this.done(this.api.saveDefaults(this.defaultsText), 'Project defaults saved. Projects use them from their next scan.');
  }

  saveUser(u: AdminUser): void {
    this.userCall(this.api.saveUser(u.name, u.role, u.disabled));
  }

  addUser(): void {
    this.userCall(this.api.saveUser(this.newUser.trim(), this.newRole, false));
    this.newUser = '';
  }

  removeUser(name: string): void {
    if (confirm('Remove the user ' + name + '? Their projects keep them as owner until you change it.')) {
      this.userCall(this.api.deleteUser(name));
    }
  }

  loadActivity(): void {
    this.api.activity(200, this.activityProject).subscribe({ next: (a) => this.activity.set(a) });
  }

  private loadUsers(): void {
    this.api.adminUsers().subscribe({ next: (u) => this.users.set(u) });
  }

  private userCall(call: Observable<unknown>): void {
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.loadUsers();
        this.loadActivity();
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private done(call: ReturnType<ApiService['admin']>, message: string): void {
    this.error.set(null);
    this.saved.set(null);
    call.subscribe({
      next: (v) => {
        this.fill(v);
        this.saved.set(message);
        this.loadActivity();
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private fill(v: AdminView): void {
    this.view.set(v);
    this.s = structuredClone(v.settings);
    for (const k of Object.keys(v.reports)) {
      this.s.reports.enabled[k] ??= false;
    }
    for (const k of Object.keys(v.summary_sections)) {
      this.s.reports.summary_sections[k] ??= false;
    }
    this.roots = v.settings.paths.extra_source_roots.join('\n');
    this.lockedText = v.settings.locked.join('\n');
    this.defaultsText = v.defaults_yaml;
  }

  readonly keep = (): number => 0;
}
