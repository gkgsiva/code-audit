import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { RunsPage } from './runs';

const run = (id: string, latest: boolean) => ({
  run_id: id, started_at: null, finished_at: null, full_scan: false, files: 10, analyzed: 2, cached: 8, loc_code: 100,
  components: 1, findings: 0, warnings: 0, latest, seconds: 1.5, git: [],
});
const empty = { added: [], removed: [], changed: [], added_count: 0, removed_count: 0, changed_count: 0 };

describe('RunsPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RunsPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) }, queryParamMap: of(convertToParamMap({})) } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('lists the runs and compares the newest two', async () => {
    const fixture = TestBed.createComponent(RunsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/runs').flush([run('20261030-020000-bbbbbb', true), run('20261030-010000-aaaaaa', false)]);
    http.expectOne('/api/projects/billing/compare?a=20261030-010000-aaaaaa&b=20261030-020000-bbbbbb').flush({
      run_a: 'a', run_b: 'b',
      entities: {
        services: { ...empty, count_a: 1, count_b: 2, added: [{ id: 's2', label: 'OrderService' }], added_count: 1 },
        screens: { ...empty, count_a: 3, count_b: 3 },
        batch_jobs: { ...empty, count_a: 0, count_b: 0 },
      },
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('20261030-020000-bbbbbb');
    expect(page.textContent).toContain('1 kind(s) changed.');
    expect(page.textContent).toContain('+ OrderService');
    expect(page.textContent).not.toContain('batch jobs');
  });

  it('says when there is no run yet', async () => {
    const fixture = TestBed.createComponent(RunsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/runs').flush([]);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No run yet');
  });
});
