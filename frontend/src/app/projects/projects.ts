import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';

import { ApiService, Project, problem } from '../api.service';
import { AuthService } from '../auth.service';

/** The projects the user may see, newest scan state on each. */
@Component({
  selector: 'app-projects',
  imports: [RouterLink, DatePipe],
  templateUrl: './projects.html',
})
export class Projects implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);

  readonly projects = signal<Project[] | null>(null);
  readonly error = signal<string | null>(null);
  readonly canCreate = computed(() => ['admin', 'owner'].includes(this.auth.me()?.role ?? ''));

  ngOnInit(): void {
    this.api.projects().subscribe({
      next: (p) => this.projects.set(p),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }
}
