/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright 2026 Uzi Cohen
 * Ported to plain JavaScript for Playwright Toolkit from microsoft/playwright-vscode src/common.ts.
 * `vscode.postMessage` goes to the plugin through the JCEF query bridge (window.__pwSend, defined by the plugin).
 */

// eslint-disable-next-line no-unused-vars
var vscode = {
  postMessage: function(msg) { window.__pwSend(JSON.stringify(msg)); },
};

// eslint-disable-next-line no-unused-vars
function createAction(action, options) {
  const actionElement = document.createElement('div');
  actionElement.classList.add('action');
  if (action.hidden)
    return null;
  if (action.disabled)
    actionElement.setAttribute('disabled', 'true');
  const label = document.createElement('label');
  if (!action.disabled) {
    label.addEventListener('click', () => {
      vscode.postMessage({ method: 'execute', params: { command: label.getAttribute('command') } });
    });
  }
  label.setAttribute('role', 'button');
  if (action.disabled)
    label.setAttribute('aria-disabled', 'true');
  label.setAttribute('command', action.command);
  const svg = document.createElement('svg');
  label.appendChild(svg);
  svg.outerHTML = action.svg || '<svg></svg>';
  if (!(options && options.omitText) && action.text)
    label.appendChild(document.createTextNode(action.text));
  label.title = action.title || action.text;
  actionElement.appendChild(label);
  return actionElement;
}
