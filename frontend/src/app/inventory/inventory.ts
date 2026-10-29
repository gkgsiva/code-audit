import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, ComponentRow, Inventory as InventoryData, problem } from '../api.service';

const PAGE = 100;

/** Every file of the newest finished scan, with filters and lines of code per language. */
@Component({
  selector: 'app-inventory',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink],
  templateUrl: './inventory.html',
})
export class Inventory implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  id = '';
  language = '';
  category = '';
  component = '';
  q = '';
  readonly page = signal(0);
  readonly data = signal<InventoryData | null>(null);
  readonly components = signal<ComponentRow[]>([]);
  readonly error = signal<string | null>(null);

  readonly pages = computed(() => Math.max(1, Math.ceil((this.data()?.total ?? 0) / PAGE)));
  readonly totals = computed(() => {
    const rows = this.data()?.languages ?? [];
    return {
      files: rows.reduce((n, r) => n + r.files, 0),
      locCode: rows.reduce((n, r) => n + r.locCode, 0),
      locComment: rows.reduce((n, r) => n + r.locComment, 0),
      locBlank: rows.reduce((n, r) => n + r.locBlank, 0),
    };
  });
  // the language and category lists stay complete while a filter is on
  readonly languages = signal<string[]>([]);
  readonly categories = signal<string[]>([]);

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.component = this.route.snapshot.queryParamMap.get('component') ?? '';
    this.api.components(this.id).subscribe({ next: (c) => this.components.set(c.components) });
    this.load();
  }

  filter(): void {
    this.page.set(0);
    this.load();
  }

  clear(): void {
    this.language = this.category = this.component = this.q = '';
    this.filter();
  }

  go(page: number): void {
    this.page.set(page);
    this.load();
  }

  componentName(id: string | null): string {
    return this.components().find((c) => c.id === id)?.name ?? id ?? '';
  }

  private load(): void {
    this.error.set(null);
    const filter = { language: this.language, category: this.category, component: this.component, q: this.q, page: this.page(), size: PAGE };
    this.api.inventory(this.id, filter).subscribe({
      next: (d) => {
        this.data.set(d);
        if (!this.languages().length) {
          this.languages.set(d.languages.map((l) => l.name).sort());
          this.categories.set(d.categories.map((c) => c.name).sort());
        }
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }
}
