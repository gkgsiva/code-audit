import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { InputsPage } from './inputs';

const inputs = {
  inputs_dir: 'audit-inputs',
  kinds: [
    { kind: 'schemas', label: 'Database schemas', folder: 'audit-inputs/schemas', files: [{ name: 'orders.sql', size: 1200 }] },
    { kind: 'autosys', label: 'Autosys job definitions', folder: 'audit-inputs/autosys', files: [] },
  ],
};

describe('InputsPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InputsPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('lists each kind and uploads a file', async () => {
    const fixture = TestBed.createComponent(InputsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'contributor' });
    http.expectOne('/api/projects/billing/inputs').flush(inputs);
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('orders.sql (1,200 bytes)');
    expect(page.textContent).toContain('Not provided.');
    expect(page.querySelectorAll('input[type="file"]').length).toBe(2);

    const file = new File(['insert_job: a'], 'jobs.jil');
    const input = { files: [file], value: 'x' } as unknown as HTMLInputElement;
    fixture.componentInstance.upload(inputs.kinds[1], { target: input } as unknown as Event);
    const req = http.expectOne('/api/projects/billing/inputs/autosys/jobs.jil');
    expect(req.request.method).toBe('PUT');
    req.flush({ saved: 'audit-inputs/autosys/jobs.jil', size: 13 });
    http.expectOne('/api/projects/billing/inputs').flush(inputs);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.textContent).toContain('Added to Autosys job definitions');
  });

  it('hides the buttons from viewers', async () => {
    const fixture = TestBed.createComponent(InputsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'viewer' });
    http.expectOne('/api/projects/billing/inputs').flush(inputs);
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('input[type="file"], button').length).toBe(0);
  });
});
