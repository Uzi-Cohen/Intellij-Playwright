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
 * Ported to plain JavaScript for Playwright Toolkit from microsoft/playwright-vscode src/locatorsView.script.ts.
 * Added: the 'extra' action slots next to each field (insert at caret).
 */

const locatorInput = document.getElementById('locator');
const ariaTextArea = document.getElementById('ariaSnapshot');
const copyToClipboardCheckbox = document.getElementById('copyToClipboardCheckbox');

locatorInput.addEventListener('input', () => {
  vscode.postMessage({ method: 'locatorChanged', params: { locator: locatorInput.value } });
});

ariaTextArea.addEventListener('input', () => {
  vscode.postMessage({ method: 'ariaSnapshotChanged', params: { ariaSnapshot: ariaTextArea.value } });
});

copyToClipboardCheckbox.addEventListener('change', () => {
  vscode.postMessage({ method: 'toggle', params: { setting: 'pickLocatorCopyToClipboard' } });
});

window.addEventListener('message', event => {
  const locatorError = document.getElementById('locatorError');
  const ariaSnapshotError = document.getElementById('ariaSnapshotError');
  const ariaSection = document.getElementById('ariaSection');
  const actionsElement = document.getElementById('actions');
  const actions2Element = document.getElementById('actions-2');
  const locatorExtra = document.getElementById('locatorExtra');
  const ariaExtra = document.getElementById('ariaExtra');

  const { method, params } = event.data;
  if (method === 'update') {
    // Do not move the caret while the user is typing in a field.
    if (document.activeElement !== locatorInput)
      locatorInput.value = params.locator.locator;
    locatorError.textContent = params.locator.error || '';
    locatorError.style.display = params.locator.error ? 'inherit' : 'none';
    if (document.activeElement !== ariaTextArea)
      ariaTextArea.value = params.ariaSnapshot.yaml;
    ariaSnapshotError.textContent = params.ariaSnapshot.error || '';
    ariaSnapshotError.style.display = params.ariaSnapshot.error ? 'inherit' : 'none';
    ariaSection.style.display = params.hideAria ? 'none' : 'flex';
  } else if (method === 'actions') {
    actionsElement.textContent = '';
    actions2Element.textContent = '';
    locatorExtra.textContent = '';
    ariaExtra.textContent = '';
    for (const action of params.actions) {
      const actionElement = createAction(action, { omitText: true });
      if (!actionElement)
        continue;
      const target = {
        'actions-2': actions2Element,
        'locatorExtra': locatorExtra,
        'ariaExtra': ariaExtra,
      }[action.location] || actionsElement;
      target.appendChild(actionElement);
    }
  } else if (method === 'settings') {
    if ('pickLocatorCopyToClipboard' in params.settings)
      copyToClipboardCheckbox.checked = params.settings.pickLocatorCopyToClipboard;
  }
});

vscode.postMessage({ method: 'ready', params: {} });
