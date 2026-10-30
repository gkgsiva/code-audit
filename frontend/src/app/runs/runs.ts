import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiService, Comparison, EntityDiff, RunRow, problem } from '../api.service';

/** A kind of fact in the comparison, with the lines to show for it. */
interface DiffRow extends EntityDiff {
  entity: string;
  items: string[];
}

/** Every run of the project and what changed between two of them (the Python site's Compare page). */
@Component({
  selector: 'app-runs',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink],
  templateUrl: './runs.html',
})
export class RunsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  id = '';
  a = '';
  b = '';
  readonly runs = signal<RunRow[]>([]);
  readonly loaded = signal(false);
  readonly diff = signal<Comparison | null>(null);
  readonly error = signal<string | null>(null);

  /** Kinds present in either run; the ones that changed carry their added, removed and changed lines. */
  readonly rows = computed<DiffRow[]>(() => {
    const d = this.diff();
    if (!d) {
      return [];
    }
    return Object.entries(d.entities)
      .filter(([, e]) => e.count_a || e.count_b)
      .map(([entity, e]) => ({
        ...e,
        entity,
        items: [
          ...e.added.map((x) => '+ ' + x.label),
          ...e.removed.map((x) => '− ' + x.label),
          ...e.changed.map((x) => '~ ' + x.label + ' (' + Object.keys(x.fields ?? {}).join(', ') + ')'),
        ],
      }));
  });
  readonly changed = computed(() => this.rows().filter((r) => r.added_count || r.removed_count || r.changed_count));

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.runs(this.id).subscribe({
      next: (runs) => {
        this.runs.set(runs);
        this.loaded.set(true);
        this.route.queryParamMap.subscribe((q) => {
          if (runs.length < 2) {
            return;
          }
          this.a = q.get('a') ?? runs[1].run_id;
          this.b = q.get('b') ?? runs[0].run_id;
          this.compare();
        });
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  pick(): void {
    this.router.navigate([], { queryParams: { a: this.a, b: this.b } });
  }

  label(entity: string): string {
    return entity.replace(/_/g, ' ');
  }

  private compare(): void {
    this.error.set(null);
    this.api.compare(this.id, this.a, this.b).subscribe({
      next: (d) => this.diff.set(d),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }
}
