import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ApiService, SystemInfo, Usage, problem } from '../api.service';
import { size } from '../admin/admin';

const POLL_MS = 10000;

/** Usage of the site from the activity log, and the server's memory, CPU and disk (the last hour). */
@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink],
  templateUrl: './dashboard.html',
})
export class DashboardPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  days = 30;
  readonly usage = signal<Usage | null>(null);
  readonly system = signal<SystemInfo | null>(null);
  readonly error = signal<string | null>(null);
  readonly size = size;
  private timer: ReturnType<typeof setTimeout> | null = null;

  /** The largest daily scan count, for the bar widths. */
  readonly maxScans = computed(() => Math.max(1, ...(this.usage()?.series ?? []).map((d) => d.scans_done + d.scans_failed + d.scans_cancelled)));

  /** SVG points of the CPU and memory lines (0 to 100 % of the chart height). */
  readonly cpuLine = computed(() => this.line((p) => p.cpu_percent));
  readonly memLine = computed(() => this.line((p) => (p.memory_used && p.memory_limit ? (100 * p.memory_used) / p.memory_limit : null)));

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.timer && clearTimeout(this.timer));
    this.loadUsage();
    this.loadSystem();
  }

  loadUsage(): void {
    this.api.usage(this.days).subscribe({ next: (u) => this.usage.set(u), error: (err: HttpErrorResponse) => this.error.set(problem(err)) });
  }

  memoryPercent(s: SystemInfo): number | null {
    return s.memory.used && s.memory.limit ? Math.round((100 * s.memory.used) / s.memory.limit) : null;
  }

  uptime(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return h + ' h ' + m + ' min';
  }

  private loadSystem(): void {
    this.api.system().subscribe({
      next: (s) => {
        this.system.set(s);
        this.timer = setTimeout(() => this.loadSystem(), POLL_MS);
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private line(value: (p: SystemInfo['history'][number]) => number | null): string {
    const h = this.system()?.history ?? [];
    const step = h.length > 1 ? 300 / (h.length - 1) : 0;
    return h
      .map((p, i) => [i * step, value(p)] as const)
      .filter(([, v]) => v !== null)
      .map(([x, v]) => x.toFixed(1) + ',' + (60 - (60 * Math.min(100, v as number)) / 100).toFixed(1))
      .join(' ');
  }
}
