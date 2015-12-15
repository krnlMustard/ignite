/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import angular from 'angular'

angular
    .module('ignite-console.terms', [

    ])
    .provider('igniteTerms', function() {
        var _rows = [
            'Apache Ignite Web Console, version 1.0.0 beta',
            '© 2015 The Apache Software Foundation.',
            'Apache, Apache Ignite, the Apache feather and the Apache Ignite logo are trademarks of The Apache Software Foundation.'
        ];

        var _state;

        this.footerRows = function(rows) {
            _rows = rows;
        };

        this.termsState = function(state) {
            _state = state;
        };

        this.$get = [function() {
            return {
                footerRows: _rows,
                termsState: _state
            };
        }]
    })
    .directive('igniteTerms', ['igniteTerms', function(igniteTerms) {
        function controller() {
            var ctrl = this;

            ctrl.footerRows = igniteTerms.footerRows;
            ctrl.termsState = igniteTerms.termsState;
        }

        return {
            restrict: 'A',
            controller: controller,
            controllerAs: 'terms'
        }
    }]);