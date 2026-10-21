import { Body, Controller, Get, Param, Post, Query } from '@nestjs/common';
import { InvoicesService } from './invoices.service';

@Controller('invoices')
export class InvoicesController {
  constructor(private readonly invoices: InvoicesService) {}

  @Get()
  list(@Query('order') orderId?: string) {
    return this.invoices.forOrder(orderId ? Number(orderId) : undefined);
  }

  @Get(':id')
  get(@Param('id') id: string) {
    return this.invoices.get(Number(id));
  }

  @Post()
  create(@Body() body: { orderId: number; amount: number }) {
    return this.invoices.issue(body.orderId, body.amount);
  }
}
