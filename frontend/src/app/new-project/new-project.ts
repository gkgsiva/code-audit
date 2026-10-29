import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { ApiService, Folders, problem } from '../api.service';

/** Name the project and pick the folder holding the code. */
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

  create(): void {
    const sourcePath = this.picked();
    if (!sourcePath) {
      this.error.set('Pick the folder holding the code');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.createProject({ title: this.title, description: this.description, sourcePath }).subscribe({
      next: (p) => this.router.navigate(['/p', p.id]),
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(problem(err));
      },
    });
  }
}
