<%@ Page Language="C#" MasterPageFile="~/Site.master" CodeBehind="Default.aspx.cs" Inherits="LegacyCrm.DefaultPage" %>
<asp:Content ContentPlaceHolderID="Main" runat="server">
    <asp:TextBox ID="txtSearch" runat="server" />
    <asp:DropDownList ID="ddlRegion" runat="server" DataSourceID="dsRegions" />
    <asp:Button ID="btnSearch" runat="server" Text="Search" OnClick="btnSearch_Click" />
    <asp:GridView ID="gvContacts" runat="server" />
    <asp:HyperLink runat="server" NavigateUrl="~/Contact.aspx" Text="New contact" />
    <asp:SqlDataSource ID="dsRegions" runat="server" ConnectionString="<%$ ConnectionStrings:Crm %>"
        SelectCommand="SELECT REGION_ID, REGION_NAME FROM CRM_REGION ORDER BY REGION_NAME" />
</asp:Content>
