import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, Enrichment, Project, ValidationMessage, problem } from '../api.service';

/**
 * The AI enrichment of the newest finished scan: the state of each stage, validating what
 * Copilot wrote, and running the stages with the model set in audit.yaml.
 */
@Component({
  selector: 'app-enrichment',
  imports: [RouterLink],
  templateUrl: './enrichment.html',
})
export class EnrichmentPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  id = '';
  readonly project = signal<Project | null>(null);
  readonly data = signal<Enrichment | null>(null);
  readonly messages = signal<ValidationMessage[] | null>(null);
  readonly note = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly canRun = computed(() => ['admin', 'owner', 'contributor'].includes(this.project()?.role ?? ''));
  readonly running = computed(() => this.data()?.job?.state === 'running');
  readonly percent = computed(() => {
    const j = this.data()?.job;
    return j && j.total > 0 ? Math.round((100 * j.done) / j.total) : null;
  });
  private timer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.project(this.id).subscribe({
      next: (p) => this.project.set(p),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
    this.destroyRef.onDestroy(() => this.stop());
    this.load();
  }

  load(): void {
    this.api.enrichment(this.id).subscribe({
      next: (e) => this.show(e),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  validate(): void {
    this.error.set(null);
    this.api.validateEnrichment(this.id).subscribe({
      next: (e) => {
        const errors = (e.messages ?? []).filter((m) => m.level === 'error').length;
        this.messages.set(e.messages ?? []);
        this.note.set(`${errors} error(s), ${(e.checked ?? []).length} stage file(s) checked.`);
        this.show(e);
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  run(stale: boolean): void {
    this.error.set(null);
    this.note.set(null);
    this.api.runEnrichment(this.id, stale).subscribe({
      next: (e) => this.show(e),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  copy(text: string): void {
    const done = () => this.note.set('Copied. Paste it into Copilot Chat.');
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text).then(done, () => this.note.set('Copy it by hand: ' + text));
    } else {
      this.note.set('Copy it by hand: ' + text);
    }
  }

  private show(e: Enrichment): void {
    this.data.set(e);
    this.stop();
    if (e.job?.state === 'running') {
      this.timer = setTimeout(() => this.load(), 1500);
    }
  }

  private stop(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
