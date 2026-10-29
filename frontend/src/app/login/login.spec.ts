import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Login } from './login';

describe('Login', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the test-login banner from the backend', async () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    http.expectOne('/api/auth/info').flush({ provider: 'dummy', label: 'Test login', loginForm: true, banner: 'Test login: anyone can log in' });
    http.expectOne('/api/health').flush({ status: 'ok', name: 'legacy-audit', version: '0.1.0', database: 'up' });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.banner')?.textContent).toContain('Test login');
    expect(page.textContent).toContain('Version 0.1.0');
  });

  it('shows the backend message when the name is refused', async () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    http.expectOne('/api/auth/info').flush({ provider: 'dummy', label: 'Test login', loginForm: true, banner: null });
    http.expectOne('/api/health').flush({ status: 'ok', name: 'legacy-audit', version: '0.1.0', database: 'up' });
    fixture.componentInstance.name = '../x';
    fixture.componentInstance.submit();
    http.expectOne('/api/auth/login').flush({ detail: 'Use letters, digits' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent).toContain('Use letters, digits');
  });
});
