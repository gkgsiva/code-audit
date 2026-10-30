import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, ComponentDetail, ComponentPart, problem } from '../api.service';

/** The kinds of fact listed on a component's page, in this order. */
const PARTS = [
  { key: 'services', title: 'Services' },
  { key: 'screens', title: 'Screens' },
  { key: 'sql_statements', title: 'SQL statements' },
  { key: 'batch_jobs', title: 'Batch jobs' },
  { key: 'integrations', title: 'Integrations' },
] as const;

/** One component of a run with its services, screens, SQL, batch jobs and integrations. */
@Component({
  selector: 'app-component',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './component.html',
})
export class ComponentPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  id = '';
  run = '';
  readonly detail = signal<ComponentDetail | null>(null);
  readonly error = signal<string | null>(null);

  /** The component's own fields, lists joined. */
  readonly fields = computed(() =>
    Object.entries(this.detail()?.component ?? {}).map(([key, value]) => ({ key, value: this.text(value) })));
  readonly parts = computed(() => {
    const d = this.detail();
    if (!d) {
      return [];
    }
    return PARTS.map((p) => {
      const part = (d[p.key] as ComponentPart | undefined) ?? { total: 0, rows: [] };
      return { ...p, total: part.total, rows: part.rows, columns: part.rows.length ? Object.keys(part.rows[0]) : [] };
    });
  });

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.run = this.route.snapshot.paramMap.get('run') ?? 'latest';
    const name = this.route.snapshot.paramMap.get('name') ?? '';
    this.api.component(this.id, this.run, name).subscribe({
      next: (d) => this.detail.set(d),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  text(value: unknown): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (Array.isArray(value)) {
      return value.map((v) => (typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v))).join(', ');
    }
    return typeof value === 'object' ? JSON.stringify(value) : String(value);
  }
}
