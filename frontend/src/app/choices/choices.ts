import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, ChoiceRow, Choices, Project, problem } from '../api.service';

/** The component types a user may pick instead of the detected one. */
export const TYPES = ['web', 'frontend', 'api', 'desktop', 'batch', 'online-mainframe', 'cft', 'script', 'config', 'report', 'library',
  'db', 'unknown'];

/** A component's folders as one key: the folders joined by "|" (what the server expects). */
export function folderKey(c: ChoiceRow): string {
  return (c.folders?.length ? c.folders : [c.path]).join('|');
}

/** Which components the next scan reads and their type overrides, saved to audit-overrides.yaml. */
@Component({
  selector: 'app-choices',
  imports: [DecimalPipe, FormsModule, RouterLink],
  templateUrl: './choices.html',
})
export class ChoicesPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  readonly types = TYPES;
  id = '';
  readonly project = signal<Project | null>(null);
  readonly data = signal<Choices | null>(null);
  readonly error = signal<string | null>(null);
  readonly saved = signal<string | null>(null);
  readonly canEdit = computed(() => ['admin', 'owner'].includes(this.project()?.role ?? ''));
  included: Record<string, boolean> = {};
  overrides: Record<string, string> = {};

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.project(this.id).subscribe({ next: (p) => this.project.set(p) });
    this.api.choices(this.id).subscribe({
      next: (d) => this.fill(d),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  key(c: ChoiceRow): string {
    return folderKey(c);
  }

  folders(c: ChoiceRow): string {
    return (c.parts?.length ? c.parts : [c.path || '(root)']).join(', ');
  }

  languages(c: ChoiceRow): string {
    return Object.entries(c.languages).map(([l, n]) => l + ' ' + n).join(', ');
  }

  group(c: ChoiceRow): string {
    const g = (c.build_group ?? []).join(' > ');
    return g + (c.packaging ? (g ? ' · ' : '') + c.packaging : '');
  }

  roots(d: Choices): string {
    return d.roots.map((r) => r.name).join(', ');
  }

  skipped(d: Choices): string {
    return d.skipped.map((k) => k.path + ' (' + k.files + ' files)').join(', ');
  }

  save(): void {
    const types: Record<string, string | null> = {};
    for (const c of this.data()?.components ?? []) {
      types[c.name] = this.overrides[c.name] || null;
    }
    this.error.set(null);
    this.api.saveChoices(this.id, this.included, types).subscribe({
      next: (d) => {
        this.fill(d);
        this.saved.set('Saved to audit-overrides.yaml. Start a new scan to apply.');
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private fill(d: Choices): void {
    this.data.set(d);
    this.included = {};
    this.overrides = {};
    for (const c of d.components) {
      this.included[folderKey(c)] = c.included;
      this.overrides[c.name] = c.type_override ?? '';
    }
  }
}
