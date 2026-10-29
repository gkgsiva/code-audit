import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, ComponentRow, Project, Scan, ScanStatus, problem } from '../api.service';

const POLL_MS = 1500;

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
  newRole = 'contributor';

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
    this.api.startScan(this.id, full).subscribe({
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
