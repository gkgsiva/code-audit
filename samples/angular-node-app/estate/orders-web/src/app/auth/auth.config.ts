// Okta sign-in for the orders front end.
export const oktaConfig = {
  issuer: 'https://acme.okta.com/oauth2/default',
  clientId: '0oa1orders',
  redirectUri: window.location.origin + '/login/callback',
};
