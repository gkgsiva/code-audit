"""typescript analyzer: Angular, AngularJS, Express, NestJS, ORMs, HTTP calls and jobs."""

from __future__ import annotations

from legacy_audit.analyzers.typescript import TypeScriptAnalyzer, url_path
from tests.analyzers.helpers import entities, run

EXPRESS = """
import express from 'express';
import cron from 'node-cron';
import axios from 'axios';
import { ordersRouter } from './routes/orders';

const app = express();
const router = express.Router();
router.get('/:id', async (req, res) => {
  const rows = await pool.query('SELECT id, status FROM orders WHERE id = $1', [req.params.id]);
  res.json(rows);
});
router.delete('/:id', handler);
app.use('/api/orders', router);
app.get('/health', (req, res) => res.send('ok'));

export async function charge(id: string) {
  return axios.post('https://payments.acme.local/api/v1/charges', { id });
}

cron.schedule('0 2 * * *', async () => {
  await pool.query("DELETE FROM orders WHERE status = 'CLOSED'");
});
app.listen(3000);
"""


def test_express_routes_sql_http_and_cron() -> None:
    facts = run(TypeScriptAnalyzer(), "orders-api/src/server.ts", EXPRESS)
    routes = sorted(s.name for s in entities(facts, "services"))
    assert routes == ["DELETE /:id", "GET /:id", "GET /health"]
    mounts = [s for s in facts.symbols if s.kind == "node-route-mount"]
    assert mounts and mounts[0].data["prefix"] == "/api/orders"
    sql = entities(facts, "sql_statements")
    assert sorted(s.type for s in sql) == ["DELETE", "SELECT"]
    assert all(s.source == "node-db" for s in sql)
    targets = [i.target for i in entities(facts, "integrations")]
    assert "https://payments.acme.local/api/v1/charges" in targets
    jobs = entities(facts, "batch_jobs")
    assert [(j.scheduler, j.schedule) for j in jobs] == [("node-cron", "0 2 * * *")]
    assert [e.kind for e in entities(facts, "entry_points")] == ["node-server"]
    assert any(r.kind == "uses" and r.target_kinds == ["module"] for r in facts.refs)


NEST = """
import { Controller, Get, Post, Param } from '@nestjs/common';
import { Cron } from '@nestjs/schedule';

@Controller('invoices')
export class InvoicesController {
  @Get()
  list() { return this.prisma.invoice.findMany(); }

  @Get(':id')
  one(@Param('id') id: string) { return this.prisma.invoice.findUnique({ where: { id } }); }

  @Post()
  create() { return this.prisma.invoice.create({ data: {} }); }

  @Cron('0 30 1 1 * *', { name: 'month-end-close' })
  close() { return this.prisma.$executeRaw`UPDATE invoices SET closed = true`; }
}
"""


def test_nestjs_controller_prisma_and_cron() -> None:
    facts = run(TypeScriptAnalyzer(), "billing-api/src/invoices.controller.ts", NEST)
    routes = sorted(s.name for s in entities(facts, "services") if s.kind == "nest-route")
    assert routes == ["GET /invoices", "GET /invoices/:id", "POST /invoices"]
    sources = sorted({s.source for s in entities(facts, "sql_statements")})
    assert sources == ["node-db", "prisma"]
    jobs = entities(facts, "batch_jobs")
    assert [(j.name, j.schedule) for j in jobs] == [("month-end-close", "30 1 1 * *")]


ANGULAR = """
import { Component } from '@angular/core';
import { Routes } from '@angular/router';
import { HttpClient } from '@angular/common/http';

@Component({ selector: 'app-order-list', templateUrl: './order-list.component.html' })
export class OrderListComponent {
  constructor(private http: HttpClient) {}
  load() { return this.http.get('/api/orders?status=OPEN'); }
}

export const routes: Routes = [
  { path: 'orders', component: OrderListComponent },
];
"""


def test_angular_component_routes_and_calls() -> None:
    facts = run(TypeScriptAnalyzer(), "web/src/app/orders/order-list.component.ts", ANGULAR)
    renders = [r.target for r in facts.refs if r.kind == "renders"]
    assert renders == ["web/src/app/orders/order-list.component.html"]
    assert any(s.kind == "angular-component" for s in facts.symbols)
    forwards = [r for r in facts.refs if r.kind == "forwards"]
    assert forwards and forwards[0].target == "OrderListComponent"
    calls = [r.target for r in facts.refs if r.kind == "calls"]
    assert calls == ["/api/orders"]


ANGULARJS = """
angular.module('portal', ['ngRoute'])
  .config(function ($routeProvider) {
    $routeProvider.when('/accounts', {
      templateUrl: 'views/accounts.html', controller: 'AccountsCtrl'
    });
  })
  .controller('AccountsCtrl', function ($scope, $http) {
    $http.get('/portal/api/accounts').then(function (r) { $scope.accounts = r.data; });
  })
  .component('accountSummary', { templateUrl: 'views/account-summary.html' });
"""


def test_angularjs_module_routes_and_components() -> None:
    facts = run(TypeScriptAnalyzer(), "portal/WebContent/js/app.js", ANGULARJS)
    kinds = {s.kind for s in facts.symbols}
    assert {"angularjs-controller", "angularjs-component"} <= kinds
    assert [e.kind for e in entities(facts, "entry_points")] == ["angularjs-app"]
    assert any(
        r.kind == "forwards" and r.target.endswith("views/accounts.html") for r in facts.refs
    )
    assert "/portal/api/accounts" in [r.target for r in facts.refs if r.kind == "calls"]


def test_url_path_strips_query_and_placeholders() -> None:
    assert url_path("/api/orders?status=OPEN") == "/api/orders"
    assert url_path("https://x.example.com/a") is None
