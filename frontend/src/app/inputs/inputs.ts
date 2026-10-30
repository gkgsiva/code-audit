import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { ApiService, InputKind, Project, problem } from '../api.service';

/** Exports that arrive after the code (schemas, schedules, vulnerability reports...): add, list, remove. */
@Component({
  selector: 'app-inputs',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './inputs.html',
})
export class InputsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  id = '';
  readonly project = signal<Project | null>(null);
  readonly kinds = signal<InputKind[]>([]);
  readonly folder = signal('');
  readonly error = signal<string | null>(null);
  readonly saved = signal<string | null>(null);
  readonly canEdit = computed(() => ['admin', 'owner', 'contributor'].includes(this.project()?.role ?? ''));

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.project(this.id).subscribe({ next: (p) => this.project.set(p) });
    this.load();
  }

  upload(kind: InputKind, event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (!files.length) {
      return;
    }
    this.error.set(null);
    forkJoin(files.map((f) => this.api.uploadInput(this.id, kind.kind, f))).subscribe({
      next: () => {
        input.value = '';
        this.saved.set('Added to ' + kind.label + '. Start a new scan to include it.');
        this.load();
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  remove(kind: InputKind, name: string): void {
    if (!confirm('Remove ' + name + ' from ' + kind.label + '?')) {
      return;
    }
    this.api.deleteInput(this.id, kind.kind, name).subscribe({
      next: () => this.load(),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private load(): void {
    this.api.inputs(this.id).subscribe({
      next: (d) => {
        this.kinds.set(d.kinds);
        this.folder.set(d.inputs_dir);
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }
}
