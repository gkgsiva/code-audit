import { Component, Input, OnInit } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { OrderService } from './order.service';

@Component({
  selector: 'app-order-detail',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './order-detail.component.html',
})
export class OrderDetailComponent implements OnInit {
  @Input() id!: string;
  form = this.fb.group({ customerId: [0], status: ['OPEN'], total: [0] });

  constructor(private fb: FormBuilder, private orders: OrderService) {}

  ngOnInit(): void {
    if (this.id) {
      this.orders.get(Number(this.id)).subscribe((o) => this.form.patchValue(o));
    }
  }

  save(): void {
    const value = this.form.getRawValue();
    if (value.total !== null && value.total < 0) {
      return;
    }
    this.orders.save({ id: Number(this.id), ...value } as never).subscribe();
  }
}
