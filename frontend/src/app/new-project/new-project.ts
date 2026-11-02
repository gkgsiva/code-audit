import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { ApiService, Folders, problem } from '../api.service';

/** Name the project and pick the folder holding the code, or give a Git repository. */
@Component({
  selector: 'app-new-project',
  imports: [FormsModule, RouterLink],
  templateUrl: './new-project.html',
})
export class NewProject implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  title = '';
  description = '';
  kind: 'local' | 'git' = 'local';
  gitUrl = '';
  gitRef = '';
  gitPaths = '';
  gitToken = '';
  readonly folders = signal<Folders | null>(null);
  readonly picked = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  ngOnInit(): void {
    this.open('');
  }

  open(path: string): void {
    this.error.set(null);
    this.api.sources(path).subscribe({
      next: (f) => {
        this.folders.set(f);
        if (f.path) {
          this.picked.set(f.path);
        }
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  ready(): boolean {
    return !!this.title.trim() && (this.kind === 'git' ? !!this.gitUrl.trim() : !!this.picked());
  }

  create(): void {
    const sourcePath = this.picked();
    if (this.kind === 'local' && !sourcePath) {
      this.error.set('Pick the folder holding the code');
      return;
    }
    const body = this.kind === 'git'
      ? { title: this.title, description: this.description, git: { url: this.gitUrl.trim(), ref: this.gitRef.trim() || null,
          paths: this.gitPaths.split(',').map((s) => s.trim()).filter(Boolean), tokenEnv: this.gitToken.trim() || null } }
      : { title: this.title, description: this.description, sourcePath: sourcePath ?? '' };
    this.busy.set(true);
    this.error.set(null);
    this.api.createProject(body).subscribe({
      next: (p) => this.router.navigate(['/p', p.id]),
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(problem(err));
      },
    });
  }
}
