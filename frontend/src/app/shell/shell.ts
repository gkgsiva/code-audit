import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { ApiService } from '../api.service';
import { AuthService } from '../auth.service';
import { Icon } from './icon';

/** One link in the side bar. */
interface NavLink {
  label: string;
  icon: string;
  link: string[];
  /** Highlight only on an exact URL match (the project overview). */
  exact?: boolean;
}

/** The side bar and top bar around every page after login, and the page itself. */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon],
  template: `
    <div class="app" [class.menu-open]="menuOpen()">
      <aside class="side" aria-label="Main menu">
        <a routerLink="/" class="logo" (click)="menuOpen.set(false)">
          <span class="mark"><app-icon name="search" /></span>legacy-audit
        </a>
        <nav>
          @for (l of mainLinks; track l.label) {
            <a class="nav" [routerLink]="l.link" routerLinkActive="on" [routerLinkActiveOptions]="{ exact: true }"
               (click)="menuOpen.set(false)"><app-icon [name]="l.icon" />{{ l.label }}</a>
          }
          @if (projectId(); as id) {
            <div class="sec">This project</div>
            <a class="proj" [routerLink]="['/p', id]" (click)="menuOpen.set(false)">
              {{ projectTitle() || id }}
              @if (projectRole()) { <small>{{ projectRole() }}</small> }
            </a>
            @for (l of projectLinks(); track l.label) {
              <a class="nav" [routerLink]="l.link" routerLinkActive="on" [routerLinkActiveOptions]="{ exact: !!l.exact }"
                 (click)="menuOpen.set(false)"><app-icon [name]="l.icon" />{{ l.label }}</a>
            }
          }
          @if (auth.me()?.role === 'admin') {
            <div class="sec">Admin</div>
            <a class="nav" routerLink="/admin" routerLinkActive="on" [routerLinkActiveOptions]="{ exact: true }"
               (click)="menuOpen.set(false)"><app-icon name="shield" />Admin</a>
            <a class="nav" routerLink="/admin/dashboard" routerLinkActive="on" (click)="menuOpen.set(false)"><app-icon name="chart" />Dashboard</a>
          }
        </nav>
        @if (version()) {
          <div class="foot">Version {{ version() }}</div>
        }
      </aside>
      <div class="side-backdrop" (click)="menuOpen.set(false)"></div>
      <div class="main">
        <header class="topbar">
          <div class="crumbs">
            <button type="button" class="menu-btn" aria-label="Open the menu" (click)="menuOpen.set(!menuOpen())"><app-icon name="menu" /></button>
            <a routerLink="/">Projects</a>
            @if (projectId(); as id) {
              <span class="sep">/</span><a [routerLink]="['/p', id]"><b>{{ projectTitle() || id }}</b></a>
            }
          </div>
          <div class="user">
            <div class="user-name">
              <div>{{ auth.me()?.name }}</div>
              <div class="role">{{ auth.me()?.role }}</div>
            </div>
            <div class="avatar" aria-hidden="true">{{ initial() }}</div>
            <button type="button" class="btn small" (click)="logout()">Log out</button>
          </div>
        </header>
        <main class="page"><router-outlet /></main>
      </div>
    </div>
  `,
  styles: `
    .app { display: grid; grid-template-columns: 248px minmax(0, 1fr); min-height: 100vh;
      background: linear-gradient(to right, var(--side) 248px, transparent 248px); }
    .side { background: var(--side); color: var(--side-text); padding: 18px 14px; display: flex; flex-direction: column;
      position: sticky; top: 0; height: 100vh; overflow-y: auto; }
    .logo { display: flex; align-items: center; gap: 10px; padding: 4px 10px 18px; color: #fff; font-weight: 700;
      font-size: 16px; letter-spacing: -.01em; text-decoration: none; }
    .mark { width: 32px; height: 32px; border-radius: 9px; background: linear-gradient(135deg, #6366f1, #22d3ee);
      display: grid; place-items: center; color: #fff; }
    nav { display: flex; flex-direction: column; gap: 2px; }
    .sec { font-size: 11px; text-transform: uppercase; letter-spacing: .08em; color: var(--side-muted);
      padding: 16px 10px 6px; font-weight: 600; }
    .nav { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 9px; color: var(--side-text);
      font-weight: 500; text-decoration: none; }
    .nav app-icon { opacity: .8; }
    .nav:hover { background: var(--side-active); color: #fff; }
    .nav.on { background: var(--side-active); color: #fff; box-shadow: inset 3px 0 0 #818cf8; }
    .nav.on app-icon { opacity: 1; color: #a5b4fc; }
    .proj { display: block; margin: 0 0 6px; padding: 8px 10px; border-radius: 10px; background: var(--side-card); color: #fff;
      font-weight: 600; font-size: 13px; text-decoration: none; overflow-wrap: anywhere; }
    .proj small { display: block; color: var(--side-muted); font-weight: 500; }
    .foot { margin-top: auto; padding: 16px 10px 0; color: var(--side-muted); font-size: 12px; }
    .main { display: flex; flex-direction: column; min-width: 0; }
    .topbar { position: sticky; top: 0; z-index: 5; height: 60px; display: flex; align-items: center; justify-content: space-between;
      gap: 1rem; padding: 0 28px; background: var(--panel); border-bottom: 1px solid var(--line); }
    .crumbs { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: 13.5px; min-width: 0; }
    .crumbs a { color: var(--muted); text-decoration: none; white-space: nowrap; }
    .crumbs a:hover { color: var(--text); }
    .crumbs b { color: var(--text); font-weight: 600; }
    .user { display: flex; align-items: center; gap: 10px; font-size: 13.5px; }
    .user-name { text-align: right; font-weight: 600; line-height: 1.25; }
    .role { font-size: 11.5px; color: var(--muted); font-weight: 500; }
    .avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--accent-soft); color: var(--accent);
      display: grid; place-items: center; font-weight: 700; text-transform: uppercase; }
    .page { padding: 28px; max-width: 1280px; width: 100%; }
    .menu-btn { display: none; border: 0; background: none; color: var(--text); padding: 4px; cursor: pointer; }
    .side-backdrop { display: none; }
    @media (max-width: 56rem) {
      .app { grid-template-columns: minmax(0, 1fr); background: none; }
      .side { position: fixed; left: 0; z-index: 20; width: 260px; transform: translateX(-100%); transition: transform .2s; }
      .menu-open .side { transform: none; }
      .menu-open .side-backdrop { display: block; position: fixed; inset: 0; z-index: 15; background: rgba(15, 23, 42, .45); }
      .menu-btn { display: inline-flex; }
      .topbar { padding: 0 16px; }
      .page { padding: 20px 16px; }
      .user-name { display: none; }
    }
  `,
})
export class Shell implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly version = signal('');
  readonly menuOpen = signal(false);
  /** The project the current page belongs to, from a /p/:id URL. */
  readonly projectId = signal<string | null>(null);
  readonly projectTitle = signal('');
  readonly projectRole = signal('');

  readonly initial = computed(() => (this.auth.me()?.name ?? '?').charAt(0));

  readonly mainLinks: NavLink[] = [
    { label: 'Projects', icon: 'folder', link: ['/'] },
    { label: 'Status', icon: 'pulse', link: ['/status'] },
    { label: 'Logs', icon: 'list', link: ['/logs'] },
  ];

  readonly projectLinks = computed<NavLink[]>(() => {
    const id = this.projectId();
    if (!id) {
      return [];
    }
    return [
      { label: 'Overview', icon: 'home', link: ['/p', id], exact: true },
      { label: 'Results', icon: 'chart', link: ['/p', id, 'results'] },
      { label: 'Reports', icon: 'doc', link: ['/p', id, 'reports'] },
      { label: 'AI enrichment', icon: 'spark', link: ['/p', id, 'enrichment'] },
      { label: 'Inventory', icon: 'box', link: ['/p', id, 'inventory'] },
      { label: 'Runs and compare', icon: 'clock', link: ['/p', id, 'runs'] },
      { label: 'Settings', icon: 'gear', link: ['/p', id, 'settings'] },
      { label: 'Input files', icon: 'upload', link: ['/p', id, 'inputs'] },
      { label: 'Components to scan', icon: 'check', link: ['/p', id, 'components'] },
    ];
  });

  ngOnInit(): void {
    this.auth.health().subscribe({ next: (h) => this.version.set(h.version) });
    this.follow(this.router.url);
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((e) => this.follow(e.urlAfterRedirects));
  }

  logout(): void {
    this.auth.logout().subscribe({ next: () => this.router.navigateByUrl('/login') });
  }

  /** Shows the project menu on project pages and loads the project's title once. */
  private follow(url: string): void {
    const match = /^\/p\/([^/?#]+)/.exec(url);
    const id = match ? decodeURIComponent(match[1]) : null;
    if (id === this.projectId()) {
      return;
    }
    this.projectId.set(id);
    this.projectTitle.set('');
    this.projectRole.set('');
    if (id) {
      this.api.project(id).subscribe({
        next: (p) => {
          if (this.projectId() === id) {
            this.projectTitle.set(p.title);
            this.projectRole.set(p.role ?? '');
          }
        },
        error: () => undefined,
      });
    }
  }
}
