import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { Results } from './results';

const dashboard = {
  run_id: '20261030-010000-abc123', application: 'Billing', started_at: '2026-10-30T01:00:00+00:00',
  kpis: [{ label: 'Lines of code', value: 1234, metric: 'totals.loc_code' }, { label: 'Services', value: 3, metric: 'counts.services.total' }],
  charts: { technology: { java: 1000, sql: 234 }, complexity: {}, pg_grade: { auto: 2, manual: 1 }, severity: { high: 1 } },
  coverage: { autosys: 'Autosys: not provided' },
  warnings: [],
  components: [],
  top_findings: [{ rule: 'eol-library', severity: 'high', file: 'pom.xml', line: 3, message: 'Log4j 1 is end of life' }],
  effort: { label: 'estimate — weights from config', unit: 'person-days', low: 4, likely: 8.1, high: 15.2 },
  timings: {},
  vulnerability_sources: [{ name: 'osv-scanner', status: 'imported', detail: null, database_date: null, reports: [] }],
  target: null,
};

describe('Results', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Results],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the headline numbers, effort and top findings', async () => {
    const fixture = TestBed.createComponent(Results);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/dashboard').flush({ scan: { id: 1 }, dashboard });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Lines of code');
    expect(page.textContent).toContain('8.1');
    expect(page.textContent).toContain('Log4j 1 is end of life');
    expect(page.textContent).toContain('Autosys: not provided');
    expect(page.querySelectorAll('.bar').length).toBe(5);
  });

  it('says when nothing was scanned yet', async () => {
    const fixture = TestBed.createComponent(Results);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/dashboard').flush({ scan: null, dashboard: null });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No finished scan yet');
  });
});
