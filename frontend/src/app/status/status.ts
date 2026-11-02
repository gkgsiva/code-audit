import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiService, SiteStatus, problem } from '../api.service';

const POLL_MS = 3000;

/** Free and busy job slots and the last jobs (scans, report builds, AI runs) of the user's projects. */
@Component({
  selector: 'app-status',
  imports: [DatePipe, RouterLink],
  templateUrl: './status.html',
})
export class StatusPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly status = signal<SiteStatus | null>(null);
  readonly error = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.timer && clearTimeout(this.timer));
    this.load();
  }

  load(): void {
    this.api.status().subscribe({
      next: (s) => {
        this.status.set(s);
        this.timer = setTimeout(() => this.load(), POLL_MS);
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  kind(k: string): string {
    return ({ scan: 'Scan', report: 'Reports', enrich: 'AI enrichment' } as Record<string, string>)[k] ?? k;
  }
}
