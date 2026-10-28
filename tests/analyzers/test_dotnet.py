# ruff: noqa: E501  (fixtures keep the long lines of real project files)
"""dotnet analyzer: .NET project files, NuGet, configuration, XAML/FXML, ASP.NET pages and
Windows Task Scheduler."""

from __future__ import annotations

from legacy_audit.analyzers.dotnet import DotnetAnalyzer, connection_string, framework_version
from tests.analyzers.helpers import entities, run

CSPROJ = """<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net6.0-windows</TargetFramework>
    <UseWPF>true</UseWPF>
    <LangVersion>10.0</LangVersion>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Dapper" Version="2.0.123" />
    <PackageReference Include="Microsoft.EntityFrameworkCore.SqlServer" Version="6.0.10" />
    <ProjectReference Include="..\\Shared\\Shared.csproj" />
  </ItemGroup>
</Project>"""

VBPROJ = """<?xml version="1.0" encoding="utf-8"?>
<Project ToolsVersion="12.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <MyType>WindowsForms</MyType>
    <TargetFrameworkVersion>v4.5.2</TargetFrameworkVersion>
  </PropertyGroup>
  <ItemGroup>
    <Reference Include="System.Windows.Forms" />
    <Reference Include="log4net, Version=2.0.8.0, Culture=neutral">
      <HintPath>..\\packages\\log4net.2.0.8\\lib\\net45-full\\log4net.dll</HintPath>
    </Reference>
  </ItemGroup>
</Project>"""

PACKAGES_CONFIG = """<?xml version="1.0"?>
<packages>
  <package id="Dapper" version="1.50.2" targetFramework="net452" />
</packages>"""

LOCK = """{"version": 1, "dependencies": {"net6.0-windows7.0": {
 "Dapper": {"type": "Direct", "requested": "[2.0.123, )", "resolved": "2.0.123"},
 "System.Memory": {"type": "Transitive", "resolved": "4.5.4"}}}}"""

APP_CONFIG = """<?xml version="1.0"?>
<configuration>
  <connectionStrings>
    <add name="Orders" connectionString="Data Source=ORDSRV02\\SQL2008;Initial Catalog=ORDERS;User ID=app;Password=S3cret!" providerName="System.Data.SqlClient" />
  </connectionStrings>
  <system.serviceModel><client>
    <endpoint address="http://pricing.acme.local/PricingService.svc" binding="basicHttpBinding" contract="IPricing" name="Pricing" />
  </client></system.serviceModel>
</configuration>"""

APPSETTINGS = """{"ConnectionStrings": {"Billing": "Server=BILLSRV01;Database=BILLING;Trusted_Connection=True"}}"""

MAIN_WINDOW = """<Window x:Class="Inventory.MainWindow" xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" Title="Stock">
  <StackPanel><TextBox x:Name="Search"/><ComboBox/><Button Content="Find"/><DataGrid/></StackPanel>
</Window>"""

ITEM_CARD = """<UserControl x:Class="Inventory.Controls.ItemCard" xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"><TextBlock/></UserControl>"""

APP_XAML = """<Application x:Class="Inventory.App" xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" StartupUri="MainWindow.xaml"></Application>"""

FXML = """<?xml version="1.0"?>
<VBox xmlns:fx="http://javafx.com/fxml" fx:controller="com.acme.RatesController"><TextField fx:id="amount"/><Button text="Go"/></VBox>"""

ASPX = """<%@ Page Language="C#" MasterPageFile="~/Site.master" CodeBehind="Contact.aspx.cs" Inherits="Crm.Contact" %>
<asp:TextBox ID="Name" runat="server" /><asp:DropDownList ID="Region" runat="server" DataSourceID="Regions" />
<asp:SqlDataSource ID="Regions" runat="server" SelectCommand="SELECT REGION_ID, REGION_NAME FROM CRM_REGION" />
<asp:HyperLink NavigateUrl="~/Default.aspx" runat="server" />"""

LAYOUT = """<html><body>@RenderBody()</body></html>"""

INDEX = """@model Home\n<form><input name="q"/></form>"""

TASK_XML = """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Exports the invoices.</Description><URI>\\Billing\\NightlyExport</URI></RegistrationInfo>
  <Triggers><CalendarTrigger><StartBoundary>2019-01-01T01:30:00</StartBoundary><ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay></CalendarTrigger></Triggers>
  <Actions><Exec><Command>D:\\Apps\\NightlyExport.exe</Command></Exec></Actions>
</Task>"""

SCHTASKS = """schtasks /create /tn "WeeklyPurge" /tr "D:\\Apps\\NightlyExport.exe --purge" /sc weekly /d SUN /st 03:00"""


def _run(path: str, text: str):  # type: ignore[no-untyped-def]
    return run(DotnetAnalyzer(), path, text)


def test_sdk_project_wpf_nuget_and_project_references() -> None:
    facts = _run("app/Inventory.csproj", CSPROJ)
    tech = {(t.name, t.version) for t in entities(facts, "tech_stack")}
    assert tech == {("C#", "10.0"), (".NET", "6.0"), ("WPF", None)}
    libs = {(lb.artifact, lb.version, lb.source) for lb in entities(facts, "libraries")}
    assert libs == {
        ("Dapper", "2.0.123", "nuget"),
        ("Microsoft.EntityFrameworkCore.SqlServer", "6.0.10", "nuget"),
    }
    assert [(e.name, e.kind) for e in entities(facts, "entry_points")] == [
        ("Inventory", "desktop-app")
    ]
    assert [(r.kind, r.target) for r in facts.refs] == [("depends-on", "Shared/Shared.csproj")]


def test_classic_vbproj_windows_forms_and_hint_path_packages() -> None:
    facts = _run("orders/Orders.vbproj", VBPROJ)
    tech = {(t.name, t.version) for t in entities(facts, "tech_stack")}
    assert tech == {("VB.NET", None), (".NET Framework", "4.5.2"), ("Windows Forms", None)}
    assert [(lb.artifact, lb.version) for lb in entities(facts, "libraries")] == [
        ("log4net", "2.0.8")
    ]


def test_windows_service_project_is_not_a_desktop_app() -> None:
    text = VBPROJ.replace("System.Windows.Forms", "System.ServiceProcess").replace(
        "<MyType>WindowsForms</MyType>", ""
    )
    facts = _run("svc/Mailer.vbproj", text)
    assert [e.kind for e in entities(facts, "entry_points")] == ["windows-service"]


def test_packages_config_and_lock_file() -> None:
    facts = _run("orders/packages.config", PACKAGES_CONFIG)
    assert [(lb.artifact, lb.version) for lb in entities(facts, "libraries")] == [
        ("Dapper", "1.50.2")
    ]
    facts = _run("app/packages.lock.json", LOCK)
    scopes = {lb.artifact: lb.scope for lb in entities(facts, "libraries")}
    assert scopes == {"Dapper": None, "System.Memory": "transitive"}


def test_config_connection_strings_and_wcf_client() -> None:
    facts = _run("orders/App.config", APP_CONFIG)
    found = {(i.type, i.target, i.protocol) for i in entities(facts, "integrations")}
    assert found == {
        ("DB", "sqlserver://ORDSRV02\\SQL2008/ORDERS", "ado.net"),
        ("SOAP", "http://pricing.acme.local/PricingService.svc", "WCF"),
    }
    [finding] = entities(facts, "findings")
    assert finding.rule_id == "connection-string-password" and finding.severity == "high"
    assert "S3cret" not in finding.message
    facts = _run("api/appsettings.json", APPSETTINGS)
    assert [i.target for i in entities(facts, "integrations")] == ["sqlserver://BILLSRV01/BILLING"]


def test_connection_string_parsing() -> None:
    oracle = "Provider=OraOLEDB.Oracle;Data Source=CRMPROD;User Id=x;Password=y"
    assert connection_string(oracle) == ("Oracle", "oracle://CRMPROD", True)
    access = r"Provider=Microsoft.Jet.OLEDB.4.0;Data Source=C:\Billing\regions.mdb"
    assert connection_string(access) == ("Microsoft Access", "access://regions.mdb", False)


def test_framework_monikers() -> None:
    assert framework_version("net48") == (".NET Framework", "4.8")
    assert framework_version("v4.7.2") == (".NET Framework", "4.7.2")
    assert framework_version("netcoreapp3.1") == (".NET Core", "3.1")
    assert framework_version("net8.0-windows") == (".NET", "8.0")
    assert framework_version("netstandard2.0") == (".NET Standard", "2.0")


def test_xaml_windows_user_controls_and_app_start() -> None:
    [screen] = entities(_run("app/MainWindow.xaml", MAIN_WINDOW), "screens")
    assert (screen.name, screen.technology, screen.partial) == ("MainWindow", "wpf", False)
    assert screen.fields_count == 3
    [card] = entities(_run("app/Controls/ItemCard.xaml", ITEM_CARD), "screens")
    assert card.partial
    facts = _run("app/App.xaml", APP_XAML)
    assert [e.kind for e in entities(facts, "entry_points")] == ["desktop-app"]
    assert [(r.kind, r.target) for r in facts.refs] == [("forwards", "app/MainWindow.xaml")]


def test_javafx_view_links_its_controller() -> None:
    facts = _run("fx/Rates.fxml", FXML)
    [screen] = entities(facts, "screens")
    assert (screen.name, screen.technology) == ("Rates", "javafx")
    assert [(r.target, r.target_kinds) for r in facts.refs] == [
        ("com.acme.RatesController", ["java-class"])
    ]


def test_aspx_page_data_source_sql_and_links() -> None:
    facts = _run("web/Contact.aspx", ASPX)
    [screen] = entities(facts, "screens")
    assert (screen.technology, screen.fields_count, screen.partial) == ("aspx", 2, False)
    [sql] = entities(facts, "sql_statements")
    assert (sql.source, sql.tables) == ("aspx-datasource", ["CRM_REGION"])
    assert [(r.kind, r.target) for r in facts.refs] == [("links", "Default.aspx")]


def test_razor_layouts_are_partials() -> None:
    [layout] = entities(_run("web/Views/Shared/_Layout.cshtml", LAYOUT), "screens")
    [index] = entities(_run("web/Views/Home/Index.cshtml", INDEX), "screens")
    assert layout.partial and not index.partial and index.technology == "razor"


def test_windows_task_scheduler_xml_and_schtasks() -> None:
    [job] = entities(_run("jobs/tasks/Nightly.xml", TASK_XML), "batch_jobs")
    assert (job.name, job.scheduler, job.schedule) == (
        "NightlyExport",
        "windows-task",
        "daily at 01:30",
    )
    assert job.programs == ["NightlyExport.exe"]
    [job] = entities(_run("jobs/tasks/install.cmd", SCHTASKS), "batch_jobs")
    assert (job.name, job.schedule) == ("WeeklyPurge", "weekly on SUN at 03:00")
