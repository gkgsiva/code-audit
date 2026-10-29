import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService, Health } from '../auth.service';

/** First page after login. Projects, scans and reports are added in the next phases. */
@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly health = signal<Health | null>(null);

  ngOnInit(): void {
    this.auth.health().subscribe({ next: (h) => this.health.set(h) });
  }

  logout(): void {
    this.auth.logout().subscribe({ next: () => this.router.navigateByUrl('/login') });
  }
}
