import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, Evidence, problem } from '../api.service';

/** The source lines around a piece of evidence (passwords masked), the asked line marked. */
@Component({
  selector: 'app-evidence',
  imports: [RouterLink],
  templateUrl: './evidence.html',
})
export class EvidencePage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  id = '';
  run = '';
  readonly evidence = signal<Evidence | null>(null);
  readonly error = signal<string | null>(null);

  /** Numbered lines; {@code hit} marks the line that was asked for. */
  readonly lines = computed(() => {
    const e = this.evidence();
    if (!e) {
      return [];
    }
    return e.text.split('\n').map((text, i) => ({ number: e.start + i, text, hit: e.start + i === e.line }));
  });

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.run = this.route.snapshot.paramMap.get('run') ?? 'latest';
    this.route.queryParamMap.subscribe((q) => {
      const line = Number(q.get('line')) || null;
      this.error.set(null);
      this.api.evidence(this.id, this.run, q.get('file') ?? '', line).subscribe({
        next: (e) => this.evidence.set(e),
        error: (err: HttpErrorResponse) => this.error.set(problem(err)),
      });
    });
  }
}
