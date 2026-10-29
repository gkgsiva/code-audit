import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';

import { AuthService } from '../auth.service';

/** The bar at the top of every page after login, and the page below it. */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterOutlet],
  template: `
    <header class="top">
      <a routerLink="/" class="brand">legacy-audit</a>
      <span class="who">
        @if (version()) {
          <span class="hint">Version {{ version() }}</span>
        }
        {{ auth.me()?.name }} ({{ auth.me()?.role }})
        <button type="button" class="btn" (click)="logout()">Log out</button>
      </span>
    </header>
    <main class="page"><router-outlet /></main>
  `,
  styles: `
    .top { display: flex; justify-content: space-between; align-items: center; gap: 1rem; flex-wrap: wrap;
      padding: .75rem 1.5rem; background: var(--panel); border-bottom: 1px solid var(--line); }
    .brand { font-weight: 700; color: var(--text); text-decoration: none; }
    .who { display: flex; gap: .75rem; align-items: center; color: var(--muted); flex-wrap: wrap; }
    .page { max-width: 1200px; margin: 1.5rem auto; padding: 0 1rem; }
  `,
})
export class Shell implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly version = signal('');

  ngOnInit(): void {
    this.auth.health().subscribe({ next: (h) => this.version.set(h.version) });
  }

  logout(): void {
    this.auth.logout().subscribe({ next: () => this.router.navigateByUrl('/login') });
  }
}
