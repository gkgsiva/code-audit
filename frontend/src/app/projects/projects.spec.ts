import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../auth.service';
import { Projects } from './projects';

describe('Projects', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Projects],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('lists projects with their last scan', async () => {
    TestBed.inject(AuthService).me.set({ name: 'siva', role: 'owner' });
    const fixture = TestBed.createComponent(Projects);
    fixture.detectChanges();
    http.expectOne('/api/projects').flush([
      {
        id: 'billing', title: 'Billing', description: null, owner: 'siva', members: {}, role: 'owner', sourcePath: '/sources/billing',
        createdAt: '2026-10-29T10:00:00Z', lastDone: null,
        lastScan: { id: 1, status: 'done', files: 31, locCode: 1200, queuedAt: '2026-10-29T10:00:00Z' },
      },
    ]);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Billing');
    expect(page.querySelector('.badge')?.textContent).toContain('done');
    expect(page.textContent).toContain('New project');
  });

  it('tells a viewer to ask an owner when there are no projects', async () => {
    TestBed.inject(AuthService).me.set({ name: 'vic', role: 'viewer' });
    const fixture = TestBed.createComponent(Projects);
    fixture.detectChanges();
    http.expectOne('/api/projects').flush([]);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Ask a project owner');
    expect(page.textContent).not.toContain('New project');
  });
});
