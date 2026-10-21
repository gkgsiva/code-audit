angular.module('portal')
  .controller('AccountsCtrl', ['$scope', '$http', function ($scope, $http) {
    $scope.search = function () {
      $http.get('/portal/api/accounts', { params: { name: $scope.name } })
        .then(function (r) { $scope.accounts = r.data; });
    };
  }])
  .controller('AccountDetailCtrl', ['$scope', '$http', '$routeParams', function ($scope, $http, $routeParams) {
    $http.get('/portal/api/accounts/' + $routeParams.id).then(function (r) {
      $scope.account = r.data;
    });
    $scope.save = function () {
      if ($scope.account.email && $scope.account.email.indexOf('@') < 0) {
        return;
      }
      $http.put('/portal/api/accounts/' + $routeParams.id, $scope.account);
    };
  }]);
