import { Component, OnInit } from '@angular/core';
import { AsyncPipe, NgFor } from '@angular/common';
import { Observable } from 'rxjs';
import { Order, OrderService } from './order.service';

@Component({
  selector: 'app-order-list',
  standalone: true,
  imports: [NgFor, AsyncPipe],
  templateUrl: './order-list.component.html',
})
export class OrderListComponent implements OnInit {
  orders$!: Observable<Order[]>;
  status = '';

  constructor(private orders: OrderService) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.orders$ = this.orders.list(this.status || undefined);
  }
}
