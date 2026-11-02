import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiService, ComponentRow, Project, Scan, ScanStatus, problem } from '../api.service';

const POLL_MS = 1500;

/** Reports and summary sections an owner may hide on the project (when the admin allows it). */
export const HIDEABLE_REPORTS: Record<string, string> = {
  summary: 'Summary report', mgmt: 'Management deck', functional: 'Functional document', tech: 'Technical report',
  pdf: 'PDF copies', datapack: 'Data pack',
};
export const HIDEABLE_SECTIONS: Record<string, string> = {
  project: 'Project, tech stack and modules', data: 'Database and queries', integrations: 'Integrations', batch: 'Batches',
  security: 'Dependency vulnerabilities',
};

/** One project: start a scan, follow it live, past scans, components and who has access. */
@Component({
  selector: 'app-project',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink],
  templateUrl: './project.html',
})
export class ProjectPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  id = '';
  readonly project = signal<Project | null>(null);
  readonly status = signal<ScanStatus | null>(null);
  readonly scans = signal<Scan[]>([]);
  readonly components = signal<ComponentRow[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  readonly canScan = computed(() => ['admin', 'owner', 'contributor'].includes(this.project()?.role ?? ''));
  readonly canManage = computed(() => ['admin', 'owner'].includes(this.project()?.role ?? ''));
  readonly active = computed(() => ['queued', 'running'].includes(this.status()?.scan.status ?? ''));
  readonly percent = computed(() => {
    const p = this.status()?.progress;
    return p && p.total > 0 ? Math.round((100 * p.done) / p.total) : null;
  });
  readonly members = computed(() => Object.entries(this.project()?.members ?? {}).map(([name, role]) => ({ name, role })));

  newMember = '';
  /** For a Git project: scan this branch or tag this time only (empty: the project's own). */
  scanRef = '';
  newRole = 'contributor';

  readonly reportKeys = Object.entries(HIDEABLE_REPORTS);
  readonly sectionKeys = Object.entries(HIDEABLE_SECTIONS);
  readonly isAdmin = computed(() => this.project()?.role === 'admin');
  readonly users = signal<string[]>([]);
  readonly saved = signal<string | null>(null);
  editTitle = '';
  editDescription = '';
  editOwner = '';
  editArchived = false;
  hideReport: Record<string, boolean> = {};
  hideSection: Record<string, boolean> = {};
  confirmDelete = '';

  private timer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.destroyRef.onDestroy(() => this.stopPolling());
    this.load();
  }

  load(): void {
    this.api.project(this.id).subscribe({
      next: (p) => {
        this.project.set(p);
        this.fillEdit(p);
        if (p.lastScan) {
          this.follow(p.lastScan.id);
        }
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
    this.api.scans(this.id).subscribe({ next: (s) => this.scans.set(s) });
    this.api.components(this.id).subscribe({ next: (c) => this.components.set(c.components) });
  }

  start(full: boolean): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.startScan(this.id, full, this.scanRef.trim() || null).subscribe({
      next: (s) => {
        this.busy.set(false);
        this.status.set(s);
        this.poll(s.scan.id);
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(problem(err));
      },
    });
  }

  cancel(): void {
    const s = this.status();
    if (s) {
      this.api.cancelScan(this.id, s.scan.id).subscribe({
        next: (st) => this.status.set(st),
        error: (err: HttpErrorResponse) => this.error.set(problem(err)),
      });
    }
  }

  /** Saves the title, description, owner, archive flag and the hidden reports and sections. */
  saveProject(): void {
    const hidden = (m: Record<string, boolean>) => Object.keys(m).filter((k) => m[k]);
    this.error.set(null);
    this.api.updateProject(this.id, {
      title: this.editTitle, description: this.editDescription, owner: this.editOwner, archived: this.editArchived,
      hiddenReports: hidden(this.hideReport), hiddenSections: hidden(this.hideSection),
    }).subscribe({
      next: (p) => {
        this.project.set(p);
        this.fillEdit(p);
        this.saved.set('Project saved.');
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  deleteProject(): void {
    this.api.deleteProject(this.id, this.confirmDelete.trim()).subscribe({
      next: () => this.router.navigateByUrl('/'),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private fillEdit(p: Project): void {
    this.editTitle = p.title;
    this.editDescription = p.description ?? '';
    this.editOwner = p.owner ?? '';
    this.editArchived = !!p.archived;
    this.hideReport = Object.fromEntries((p.hiddenReports ?? []).map((k) => [k, true]));
    this.hideSection = Object.fromEntries((p.hiddenSections ?? []).map((k) => [k, true]));
    if (this.canManage() && !this.users().length) {
      this.api.userNames().subscribe({ next: (u) => this.users.set(u) });
    }
  }

  addMember(): void {
    const name = this.newMember.trim();
    if (!name) {
      return;
    }
    this.saveMembers({ ...(this.project()?.members ?? {}), [name]: this.newRole });
    this.newMember = '';
  }

  removeMember(name: string): void {
    const members = { ...(this.project()?.members ?? {}) };
    delete members[name];
    this.saveMembers(members);
  }

  private saveMembers(members: Record<string, string>): void {
    this.api.setMembers(this.id, members).subscribe({
      next: (p) => this.project.set(p),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private follow(scanId: number): void {
    this.api.scan(this.id, scanId).subscribe({
      next: (s) => {
        this.status.set(s);
        if (this.active()) {
          this.poll(scanId);
        }
      },
    });
  }

  private poll(scanId: number): void {
    this.stopPolling();
    this.timer = setTimeout(() => {
      this.api.scan(this.id, scanId).subscribe({
        next: (s) => {
          this.status.set(s);
          if (this.active()) {
            this.poll(scanId);
          } else {
            // the scan ended: refresh counts, history and components
            this.load();
          }
        },
        error: () => this.poll(scanId),
      });
    }, POLL_MS);
  }

  private stopPolling(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
