// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors
//
// Mockup page: the Light/Dark buttons (ids t-light, t-dark) theme the
// page and its phones; starts in the viewer's colour scheme.
for (const [id, t] of [['t-light','light'],['t-dark','dark']]) document.getElementById(id).addEventListener('click',()=>{document.documentElement.dataset.theme=t;document.getElementById('t-light').setAttribute('aria-pressed',t==='light');document.getElementById('t-dark').setAttribute('aria-pressed',t==='dark');});
if (matchMedia('(prefers-color-scheme: dark)').matches) document.getElementById('t-dark').click();
