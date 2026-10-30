import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { ReportsPage } from './reports';

const base = '/api/projects/billing/runs/20261030-010000-abc123';
const reports = {
  scan: { id: 1 },
  run_id: '20261030-010000-abc123',
  reports: {
    tech: `${base}/reports/tech/index.html`, functional: null, mgmt: `${base}/reports/mgmt/index.html`, summary: null,
    pdf_summary: null, pdf_mgmt: null, pdf_functional: null, pdf_tech: `${base}/reports/pdf/technical.pdf`,
  },
  datapack: `${base}/datapack.zip`,
  job: null,
};

describe('ReportsPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReportsPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('links the built reports and offers a build to contributors', async () => {
    const fixture = TestBed.createComponent(ReportsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'contributor', members: {} });
    http.expectOne('/api/projects/billing/reports').flush(reports);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const links = Array.from(page.querySelectorAll('a[target=_blank]')).map((a) => a.getAttribute('href'));
    expect(links).toEqual([`${base}/reports/mgmt/index.html`, `${base}/reports/tech/index.html`, `${base}/reports/pdf/technical.pdf`]);
    expect(page.textContent).toContain('Not built yet');
    expect(page.textContent).toContain('Download the data pack');

    (page.querySelector('button.primary') as HTMLButtonElement).click();
    const post = http.expectOne('/api/projects/billing/reports');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ type: 'all' });
    post.flush({ ...reports, job: { type: 'all', run_id: reports.run_id, user: 'ann', state: 'done', stage: '', done: 4, total: 4, messages: ['PDF copies written: 4.'], error: null, started_at: '', ended_at: 'now' } });
    await fixture.whenStable();
    expect(page.textContent).toContain('PDF copies written: 4.');
  });

  it('shows viewers the reports without build buttons', async () => {
    const fixture = TestBed.createComponent(ReportsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'viewer', members: {} });
    http.expectOne('/api/projects/billing/reports').flush(reports);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelectorAll('button').length).toBe(0);
    expect(page.textContent).toContain('Viewers can open the reports');
  });
});
