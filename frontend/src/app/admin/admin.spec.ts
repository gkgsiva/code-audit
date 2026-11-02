import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AdminPage, detail, size } from './admin';

const settings = {
  reports: { enabled: { summary: true, mgmt: true, functional: true, tech: true, pdf: true, datapack: true },
    summary_sections: { overview: true }, owners_may_hide: true },
  paths: { extra_source_roots: [], git_cache_dir: null },
  users: { open_signup: true, signup_role: 'owner', who_can_create: 'owner' },
  ai: { allowed: true },
  limits: { max_scans: 2, keep_runs: 10, scan_timeout_minutes: 240, clone_keep_days: 14, min_free_gb: 5 },
  defaults: {}, locked: [],
};

describe('AdminPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('formats sizes and activity details', () => {
    expect(size(512)).toBe('512 bytes');
    expect(size(1536)).toBe('1.5 KB');
    expect(size(null)).toBe('');
    expect(detail({ time: 't', user: 'a', action: 'scan finished', project: 'p', state: 'done', seconds: 3 })).toBe('state=done; seconds=3');
  });

  it('loads the settings and users and adds a user', async () => {
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    http.expectOne('/api/admin').flush({
      settings, defaults_yaml: '', env_locked: { 'limits.max_scans': 3 }, reports: { summary: 'Summary' },
      summary_sections: { overview: 'Overview' }, roles: ['admin', 'owner', 'contributor', 'viewer'], host: {},
    });
    http.expectOne('/api/admin/users').flush([
      { name: 'siva', role: 'admin', disabled: false, created_at: null, last_login: null, fixed: true, projects: 1 },
    ]);
    http.expectOne('/api/admin/projects').flush([]);
    http.expectOne((r) => r.url === '/api/admin/activity').flush([]);
    http.expectOne('/api/admin/health').flush({ version: '1', disk: { total: null, used: null, free: null }, projects: [], checks: [],
      queue: { slots: 2, running: 0, waiting: 0 }, source_roots: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.locked('limits.max_scans')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('siva');

    page.newUser = ' anna ';
    page.newRole = 'viewer';
    page.addUser();
    const req = http.expectOne('/api/admin/users');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'anna', role: 'viewer', disabled: false });
    req.flush({});
    http.expectOne('/api/admin/users').flush([]);
    http.expectOne((r) => r.url === '/api/admin/activity').flush([]);
  });
});
