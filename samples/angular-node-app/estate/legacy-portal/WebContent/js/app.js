// Customer portal: routes of the AngularJS 1.5 single-page application.
angular.module('portal', ['ngRoute'])
  .config(['$routeProvider', function ($routeProvider) {
    $routeProvider
      .when('/accounts', {
        templateUrl: 'views/accounts.html',
        controller: 'AccountsCtrl'
      })
      .when('/accounts/:id', {
        templateUrl: 'views/account-detail.html',
        controller: 'AccountDetailCtrl'
      })
      .otherwise({ redirectTo: '/accounts' });
  }])
  .component('accountSummary', {
    templateUrl: 'views/account-summary.html',
    controller: function ($http) {
      var ctrl = this;
      $http.get('/portal/api/accounts/summary').then(function (r) { ctrl.summary = r.data; });
    }
  });
