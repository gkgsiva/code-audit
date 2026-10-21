<%@ Page Language="C#" MasterPageFile="~/Site.master" CodeBehind="Contact.aspx.cs" Inherits="LegacyCrm.ContactPage" %>
<asp:Content ContentPlaceHolderID="Main" runat="server">
    <asp:TextBox ID="txtName" runat="server" />
    <asp:TextBox ID="txtEmail" runat="server" />
    <asp:CheckBox ID="chkOptIn" runat="server" />
    <asp:Button ID="btnSave" runat="server" Text="Save" />
</asp:Content>
