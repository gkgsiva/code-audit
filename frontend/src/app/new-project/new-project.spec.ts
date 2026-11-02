import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { NewProject } from './new-project';

describe('NewProject', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NewProject],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('creates a project on a Git repository', async () => {
    const fixture = TestBed.createComponent(NewProject);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/sources').flush({ path: null, parent: null, folders: [] });
    const page = fixture.componentInstance;
    page.title = 'Billing';
    page.kind = 'git';
    expect(page.ready()).toBe(false);
    page.gitUrl = ' https://git.example.com/team/a.git ';
    page.gitPaths = 'app, batch, ';
    page.gitToken = 'GIT_TOKEN';
    expect(page.ready()).toBe(true);
    page.create();
    const req = http.expectOne('/api/projects');
    expect(req.request.body).toEqual({ title: 'Billing', description: '',
      git: { url: 'https://git.example.com/team/a.git', ref: null, paths: ['app', 'batch'], tokenEnv: 'GIT_TOKEN' } });
  });
});
