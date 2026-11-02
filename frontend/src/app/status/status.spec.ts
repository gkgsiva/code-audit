import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { StatusPage } from './status';

describe('StatusPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StatusPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the slots and the last jobs', async () => {
    const fixture = TestBed.createComponent(StatusPage);
    fixture.detectChanges();
    http.expectOne('/api/status').flush({
      slots: 2, running: 1, waiting: 0,
      jobs: [{ kind: 'scan', project: 'billing', title: 'Full rescan', user: 'siva', state: 'running', queued_at: '2026-11-02T01:00:00Z',
        started_at: '2026-11-02T01:00:01Z', ended_at: null, wait_seconds: 1, seconds: null, error: null }],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Job slots');
    expect(text).toContain('Full rescan');
    expect(text).toContain('Scan');
    fixture.destroy();
  });
});
