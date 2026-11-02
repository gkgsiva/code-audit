import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { DashboardPage } from './dashboard';

describe('DashboardPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the usage totals for the last 30 days', async () => {
    const fixture = TestBed.createComponent(DashboardPage);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/dashboard/usage' && r.params.get('days') === '30').flush({
      days: 30, from: '2026-10-04', to: '2026-11-02', series: [],
      totals: { active_users: 2, users: 5, projects: 3, archived_projects: 1, logins: 9, scans: 4, success_percent: 75,
        scan_seconds_avg: 12, wait_seconds_avg: 0, reports: 2, enrich: 0 },
      reports_by_type: [], top_projects: [], top_users: [], failures: [],
    });
    http.expectOne('/api/admin/dashboard/system').flush({
      memory: { used: 512, limit: 1024, process: 100, heap_max: 400, source: 'cgroup' },
      cpu: { percent: 5, cpus: 2, machine_cpus: 8, load: null },
      disk: { total: null, used: null, free: null }, uptime_seconds: 3720, threads: 30, java: '25', platform: 'Linux', version: '1',
      slots: 2, jobs: { running: 0, waiting: 0 }, now: [], history: [], sample_seconds: 10,
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('2 / 5');
    expect(page.uptime(3720)).toBe('1 h 2 min');
    expect(page.memoryPercent({ memory: { used: 512, limit: 1024 } } as never)).toBe(50);
    fixture.destroy();
  });
});
