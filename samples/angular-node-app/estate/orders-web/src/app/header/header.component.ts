import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Top bar shown on every page. */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink],
  template: `
    <nav>
      <a routerLink="/orders">Orders</a>
      <a routerLink="/reports">Reports</a>
      <input type="search" placeholder="Find an order" />
    </nav>
  `,
})
export class HeaderComponent {}
