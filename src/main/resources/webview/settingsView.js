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
 * Ported to plain JavaScript for Playwright Toolkit from microsoft/playwright-vscode src/settingsView.script.ts.
 * The CONFIGS gear (toggleModels) is not included: the plugin always works on one selected config.
 */

let selectConfig;

const selectAllButton = document.getElementById('selectAll');
const unselectAllButton = document.getElementById('unselectAll');

function updateProjects(projects) {
  const projectSelector = document.getElementById('project-selector');
  if (projects.length < 2) {
    projectSelector.style.display = 'none';
    return;
  }
  projectSelector.style.display = 'flex';

  const projectsElement = document.getElementById('projects');
  projectsElement.textContent = '';
  for (const project of projects) {
    const { name, enabled } = project;
    const div = document.createElement('div');
    div.classList.add('action');
    const label = document.createElement('label');
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.checked = enabled;
    input.addEventListener('change', () => {
      vscode.postMessage({ method: 'setProjectEnabled', params: { configFile: selectConfig.configFile, projectName: name, enabled: input.checked } });
    });
    label.appendChild(input);
    label.appendChild(document.createTextNode(name || '<untitled>'));
    div.appendChild(label);
    projectsElement.appendChild(div);
  }

  const allEnabled = projects.every(p => p.enabled);
  selectAllButton.hidden = allEnabled;
  unselectAllButton.hidden = !allEnabled;
}

function setAllProjectsEnabled(enabled) {
  vscode.postMessage({ method: 'setAllProjectsEnabled', params: { configFile: selectConfig.configFile, enabled } });
}
selectAllButton.addEventListener('click', () => setAllProjectsEnabled(true));
unselectAllButton.addEventListener('click', () => setAllProjectsEnabled(false));

for (const input of Array.from(document.querySelectorAll('input[type=checkbox]'))) {
  input.addEventListener('change', () => {
    vscode.postMessage({ method: 'toggle', params: { setting: input.getAttribute('setting') } });
  });
}
for (const select of Array.from(document.querySelectorAll('select[setting]'))) {
  select.addEventListener('change', () => {
    vscode.postMessage({ method: 'set', params: { setting: select.getAttribute('setting'), value: select.value } });
  });
}

const modelsSelect = document.getElementById('models');
let configsMap = new Map();
modelsSelect.addEventListener('change', () => {
  vscode.postMessage({ method: 'selectModel', params: { configFile: modelsSelect.value } });
  const config = configsMap.get(modelsSelect.value);
  if (config) {
    selectConfig = config;
    updateProjects(config.projects);
  }
});

window.addEventListener('message', event => {
  const actionsElement = document.getElementById('actions');
  const rareActionsElement = document.getElementById('rareActions');
  const modelSelector = document.getElementById('model-selector');

  const { method, params } = event.data;
  if (method === 'settings') {
    for (const [key, value] of Object.entries(params.settings)) {
      const input = document.querySelector('input[setting=' + key + ']');
      if (input) {
        if (typeof value === 'boolean')
          input.checked = value;
        else
          input.value = value;
      }
      const select = document.querySelector('select[setting=' + key + ']');
      if (select)
        select.value = value;
    }
  } else if (method === 'actions') {
    actionsElement.textContent = '';
    rareActionsElement.textContent = '';
    for (const action of params.actions) {
      const actionElement = createAction(action);
      if (!actionElement)
        continue;
      if (action.location === 'rareActions')
        rareActionsElement.appendChild(actionElement);
      else
        actionsElement.appendChild(actionElement);
    }
  } else if (method === 'models') {
    const { configs, showModelSelector } = params;
    modelsSelect.textContent = '';
    configsMap = new Map();
    for (const config of configs) {
      configsMap.set(config.configFile, config);
      const option = document.createElement('option');
      option.value = config.configFile;
      option.textContent = config.label;
      modelsSelect.appendChild(option);
      if (config.selected) {
        selectConfig = config;
        modelsSelect.value = config.configFile;
        updateProjects(config.projects);
      }
    }
    modelSelector.style.display = showModelSelector ? 'flex' : 'none';
  }
});

vscode.postMessage({ method: 'ready', params: {} });
