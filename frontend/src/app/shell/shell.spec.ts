import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthService } from '../auth.service';
import { Shell } from './shell';

@Component({ template: '' })
class Blank {}

describe('Shell', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([{ path: '', component: Shell, children: [{ path: '', component: Blank }, { path: 'p/:id', component: Blank }] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).me.set({ name: 'siva', role: 'admin' } as never);
  });

  function links(page: HTMLElement): string[] {
    return Array.from(page.querySelectorAll('.side .nav')).map((a) => a.textContent?.trim() ?? '');
  }

  it('shows the main menu, the admin pages for an admin and the version', async () => {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    http.expectOne('/api/health').flush({ status: 'ok', name: 'legacy-audit', version: '0.1.0', database: 'up' });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(links(page)).toEqual(['Projects', 'Status', 'Logs', 'Admin', 'Dashboard']);
    expect(page.textContent).toContain('Version 0.1.0');
    expect(page.querySelector('.avatar')?.textContent).toBe('s');
  });

  it('lists the project pages and its title on a project page', async () => {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    http.expectOne('/api/health').flush({ status: 'ok', name: 'legacy-audit', version: '0.1.0', database: 'up' });
    await TestBed.inject(Router).navigateByUrl('/p/orders');
    http.expectOne('/api/projects/orders').flush({ id: 'orders', title: 'Orders app', role: 'owner' });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(links(page)).toContain('Results');
    expect(links(page)).toContain('Components to scan');
    expect(page.querySelector('.proj')?.textContent).toContain('Orders app');
    expect(page.querySelector('.crumbs')?.textContent).toContain('Orders app');
  });
});
