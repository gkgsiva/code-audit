import { Injectable } from '@nestjs/common';
import { Cron } from '@nestjs/schedule';
import { PrismaService } from '../prisma.service';

@Injectable()
export class InvoicesService {
  constructor(private readonly prisma: PrismaService) {}

  forOrder(orderId?: number) {
    return this.prisma.invoice.findMany({ where: orderId ? { orderId } : {} });
  }

  get(id: number) {
    return this.prisma.invoice.findUnique({ where: { id } });
  }

  issue(orderId: number, amount: number) {
    return this.prisma.invoice.create({ data: { orderId, amount, status: 'ISSUED', issuedAt: new Date() } });
  }

  /** Month-end close: marks the month's issued invoices as closed. */
  @Cron('0 30 1 1 * *', { name: 'month-end-close' })
  async closeMonth(): Promise<void> {
    await this.prisma.$executeRaw`UPDATE INVOICES SET status = 'CLOSED' WHERE status = 'ISSUED' AND issued_at < date_trunc('month', now())`;
  }
}
