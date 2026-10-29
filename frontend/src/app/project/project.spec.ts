import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { ProjectPage } from './project';

const project = {
  id: 'billing', title: 'Billing', description: null, owner: 'siva', members: { anna: 'viewer' }, role: 'owner',
  sourcePath: '/sources/billing', createdAt: '2026-10-29T10:00:00Z', lastScan: null, lastDone: null,
};

describe('ProjectPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('starts a scan and shows its step and log', async () => {
    const fixture = TestBed.createComponent(ProjectPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush(project);
    http.expectOne('/api/projects/billing/scans').flush([]);
    http.expectOne('/api/projects/billing/components').flush({ scan: null, components: [] });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Not scanned yet');
    expect(page.textContent).toContain('anna');

    fixture.componentInstance.start(false);
    const req = http.expectOne('/api/projects/billing/scans');
    expect(req.request.body).toEqual({ full: false });
    req.flush({
      scan: { id: 7, status: 'running', startedBy: 'siva', queuedAt: '2026-10-29T10:00:00Z' },
      progress: { stage: 'analysis', done: 5, total: 10 },
      log: ['INFO legacy_audit: Scanning'],
    });
    await fixture.whenStable();
    expect(page.textContent).toContain('Step: analysis');
    expect(page.querySelector('progress')?.getAttribute('value')).toBe('50');
    expect(page.textContent).toContain('Cancel scan');
  });
});
