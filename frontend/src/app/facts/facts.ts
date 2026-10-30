import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiService, FactPage, KINDS, Scan, problem } from '../api.service';

const PAGE = 50;

/** Every kind of fact of the newest finished scan (or of the run in ?run=), with one field filter, a search and paging. */
@Component({
  selector: 'app-facts',
  imports: [DecimalPipe, FormsModule, RouterLink],
  templateUrl: './facts.html',
})
export class Facts implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly kinds = KINDS;
  id = '';
  kind = 'findings';
  /** The run shown: empty for the newest finished scan. */
  run = '';
  /** The run the answer came from, for the links to components and source lines. */
  readonly runId = signal<string | null>(null);
  q = '';
  field = '';
  value = '';
  readonly page = signal(0);
  readonly scan = signal<Scan | null>(null);
  readonly data = signal<FactPage | null>(null);
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);
  readonly columns = signal<string[]>([]);

  readonly pages = computed(() => Math.max(1, Math.ceil((this.data()?.total ?? 0) / PAGE)));

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.run = this.route.snapshot.queryParamMap.get('run') ?? '';
    this.route.paramMap.subscribe((p) => {
      this.kind = p.get('kind') ?? 'findings';
      this.field = this.value = this.q = '';
      this.columns.set([]);
      this.page.set(0);
      this.load();
    });
  }

  pick(kind: string): void {
    this.router.navigate(['/p', this.id, 'facts', kind], { queryParams: this.runQuery });
  }

  filter(): void {
    this.page.set(0);
    this.load();
  }

  clear(): void {
    this.field = this.value = this.q = '';
    this.filter();
  }

  go(page: number): void {
    this.page.set(page);
    this.load();
  }

  get runQuery(): Record<string, string> {
    return this.run ? { run: this.run } : {};
  }

  /** The cell links to the component's page (a component's name) or to the source lines (a file). */
  link(column: string, row: Record<string, unknown>): 'component' | 'evidence' | null {
    if (!this.runId() || !row[column] || typeof row[column] !== 'string') {
      return null;
    }
    if (column === 'file') {
      return 'evidence';
    }
    if ((this.kind === 'components' && column === 'name') || column === 'component') {
      return 'component';
    }
    return null;
  }

  line(row: Record<string, unknown>): number | null {
    return typeof row['line'] === 'number' ? row['line'] : null;
  }

  /** A cell as short text: lists joined, objects as JSON, long text cut. */
  text(value: unknown): string {
    if (value === null || value === undefined) {
      return '';
    }
    let s: string;
    if (Array.isArray(value)) {
      s = value.map((v) => (typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v))).join(', ');
    } else if (typeof value === 'object') {
      s = JSON.stringify(value);
    } else {
      s = String(value);
    }
    return s.length > 300 ? s.slice(0, 300) + '…' : s;
  }

  private load(): void {
    this.error.set(null);
    const query: Record<string, string | number> = { q: this.q, limit: PAGE, offset: this.page() * PAGE, run: this.run };
    if (this.field && this.value) {
      query[this.field] = this.value;
    }
    this.api.facts(this.id, this.kind, query).subscribe({
      next: (d) => {
        this.scan.set(d.scan);
        this.runId.set(d.run_id);
        this.data.set(d.result);
        this.loaded.set(true);
        const first = d.result?.rows[0];
        if (first && !this.columns().length) {
          this.columns.set(Object.keys(first));
        }
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }
}
