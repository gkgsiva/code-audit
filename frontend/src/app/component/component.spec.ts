import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { ComponentPage } from './component';

describe('ComponentPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ComponentPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing', run: 'latest', name: 'orders-web' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the component and its SQL with links to the source', async () => {
    const fixture = TestBed.createComponent(ComponentPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/runs/latest/components/orders-web').flush({
      component: { name: 'orders-web', type: 'web', files: 12, loc_code: 900, languages: ['java', 'jsp'] },
      services: { total: 0, rows: [] },
      screens: { total: 0, rows: [] },
      sql_statements: { total: 101, rows: [{ id: 'sql1', type: 'select', pg_grade: 'auto', file: 'src/Dao.java', line: 7 }] },
      batch_jobs: { total: 0, rows: [] },
      integrations: { total: 0, rows: [] },
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Component orders-web');
    expect(page.textContent).toContain('java, jsp');
    expect(page.textContent).toContain('SQL statements (101)');
    expect(page.textContent).toContain('The first 1 are shown');
    const link = page.querySelector('a[href*="evidence"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toContain('file=src%2FDao.java');
    expect(link.getAttribute('href')).toContain('line=7');
  });
});
