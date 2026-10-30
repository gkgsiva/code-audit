import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { EvidencePage } from './evidence';

describe('EvidencePage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EvidencePage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ id: 'billing', run: 'latest' }) },
            queryParamMap: of(convertToParamMap({ file: 'build.xml', line: '3' })),
          },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('numbers the lines and marks the one asked for', async () => {
    const fixture = TestBed.createComponent(EvidencePage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing/runs/latest/evidence?file=build.xml&line=3')
      .flush({ file: 'build.xml', line: 3, start: 2, end: 4, text: 'a\nb\nc', vscode: null });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('build.xml, lines 2 to 4');
    expect(page.querySelector('.hit')?.textContent).toContain('3 b');
  });
});
