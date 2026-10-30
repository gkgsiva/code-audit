import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Facts } from './facts';

describe('Facts', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Facts],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ id: 'billing' }), queryParamMap: convertToParamMap({ run: '20261029-010000-aaaaaa' }) },
            paramMap: of(convertToParamMap({ id: 'billing', kind: 'findings' })),
          },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('reads the picked run and links components and files', async () => {
    const fixture = TestBed.createComponent(Facts);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === '/api/projects/billing/facts/findings');
    expect(req.request.params.get('run')).toBe('20261029-010000-aaaaaa');
    req.flush({
      scan: null, run_id: '20261029-010000-aaaaaa',
      result: { kind: 'findings', total: 1, rows: [{ rule: 'eol', component: 'orders-web', file: 'pom.xml', line: 3 }] },
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('td a')).map((a) => a.getAttribute('href'));
    expect(links).toContain('/p/billing/runs/20261029-010000-aaaaaa/components/orders-web');
    expect(links).toContain('/p/billing/runs/20261029-010000-aaaaaa/evidence?file=pom.xml&line=3');
  });
});
