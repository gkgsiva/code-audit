import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe, KeyValuePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiService, Dashboard, RunRow, Scan, problem } from '../api.service';

/** Where each headline number is listed in the fact browser. */
const KPI_KIND: Record<string, string> = {
  'totals.components': 'components',
  'counts.services.total': 'services',
  'counts.screens.total': 'screens',
  'counts.sql.total': 'sql_statements',
  'counts.integrations.total': 'integrations',
  'counts.batch_jobs.total': 'batch_jobs',
  'counts.vulnerable_libraries.total': 'libraries',
};

/** The results of the newest finished scan (or of the run picked): headline numbers, charts, effort, findings, coverage. */
@Component({
  selector: 'app-results',
  imports: [DecimalPipe, FormsModule, KeyValuePipe, RouterLink],
  templateUrl: './results.html',
})
export class Results implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  id = '';
  /** The run shown: empty for the newest finished scan. */
  run = '';
  readonly runs = signal<RunRow[]>([]);
  readonly scan = signal<Scan | null>(null);
  readonly data = signal<Dashboard | null>(null);
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);

  readonly charts = [
    { key: 'technology', title: 'Lines of code per language' },
    { key: 'complexity', title: 'Complexity of services, screens and jobs' },
    { key: 'pg_grade', title: 'SQL statements by PostgreSQL grade' },
    { key: 'severity', title: 'Findings by severity' },
  ] as const;

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.runs(this.id).subscribe({ next: (r) => this.runs.set(r), error: () => this.runs.set([]) });
    this.route.queryParamMap.subscribe((q) => {
      this.run = q.get('run') ?? '';
      this.error.set(null);
      this.api.dashboard(this.id, this.run).subscribe({
        next: (d) => {
          this.scan.set(d.scan);
          this.data.set(d.dashboard);
          this.loaded.set(true);
        },
        error: (err: HttpErrorResponse) => this.error.set(problem(err)),
      });
    });
  }

  pickRun(): void {
    this.router.navigate([], { queryParams: { run: this.run || null } });
  }

  /** Query parameters that keep the picked run on the links to the facts. */
  get runQuery(): Record<string, string> {
    return this.run ? { run: this.run } : {};
  }

  kindOf(metric: string): string | null {
    return KPI_KIND[metric] ?? null;
  }

  /** Bar width in percent of the largest value of the chart. */
  width(values: Record<string, number>, value: number): number {
    const max = Math.max(...Object.values(values), 1);
    return Math.max(2, Math.round((100 * value) / max));
  }

  /** Keep the chart's own order (severity, band, grade) instead of sorting by name. */
  readonly keep = (): number => 0;
}
