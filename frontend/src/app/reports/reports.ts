import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, Project, Reports, problem } from '../api.service';

/** The four reports, their PDF copies and what each is for. */
const REPORTS = [
  { key: 'summary', pdf: 'pdf_summary', title: 'Summary report', text: 'One page: project, database, integrations, batches and vulnerabilities, with charts and CSV downloads.' },
  { key: 'mgmt', pdf: 'pdf_mgmt', title: 'Management deck', text: 'Slides for management: estate, readiness, risks, plan. Use the arrow keys; N shows the speaker notes.' },
  { key: 'functional', pdf: 'pdf_functional', title: 'Functional document', text: 'What the application does: domains, use cases, business flows, screens, batches and rules.' },
  { key: 'tech', pdf: 'pdf_tech', title: 'Technical report', text: 'Every component, flow, table, SQL statement, integration, library and finding, with search and CSV.' },
] as const;

/** Build the reports of the newest finished scan, open them and download the PDFs and data. */
@Component({
  selector: 'app-reports',
  imports: [RouterLink],
  templateUrl: './reports.html',
})
export class ReportsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  id = '';
  readonly reportList = REPORTS;
  readonly project = signal<Project | null>(null);
  readonly data = signal<Reports | null>(null);
  readonly error = signal<string | null>(null);
  readonly canBuild = computed(() => ['admin', 'owner', 'contributor'].includes(this.project()?.role ?? ''));
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
    this.api.reports(this.id).subscribe({
      next: (r) => this.show(r),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  build(type: string): void {
    this.error.set(null);
    this.api.buildReports(this.id, type).subscribe({
      next: (r) => this.show(r),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private show(r: Reports): void {
    this.data.set(r);
    this.stop();
    if (r.job?.state === 'running') {
      this.timer = setTimeout(() => this.load(), 1500);
    }
  }

  private stop(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  link(key: string): string | null {
    return this.data()?.reports[key] ?? null;
  }
}
