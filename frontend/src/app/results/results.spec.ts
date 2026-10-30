import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

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
  let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  beforeEach(async () => {
    query = new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({
      imports: [Results],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) }, queryParamMap: query } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the headline numbers, effort and top findings', async () => {
    const fixture = TestBed.createComponent(Results);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/runs').flush([]);
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
    http.expectOne('/api/projects/billing/runs').flush([]);
    http.expectOne('/api/projects/billing/dashboard').flush({ scan: null, dashboard: null });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No finished scan yet');
  });

  it('shows an older run when one is picked', async () => {
    const fixture = TestBed.createComponent(Results);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/runs').flush([{ run_id: 'b', latest: true, git: [] }, { run_id: 'a', latest: false, git: [] }]);
    http.expectOne('/api/projects/billing/dashboard').flush({ scan: { id: 1 }, dashboard });
    query.next(convertToParamMap({ run: '20261029-010000-aaaaaa' }));
    http.expectOne('/api/projects/billing/dashboard?run=20261029-010000-aaaaaa').flush({ scan: { id: 1 }, dashboard: { ...dashboard, run_id: 'old' } });
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('run old');
    expect((fixture.nativeElement as HTMLElement).querySelector('select[name="run"]')).toBeTruthy();
  });
});
