/* Idiomas da página: o texto-base é o português (no próprio HTML); aqui ficam o inglês e o espanhol.
   Cada elemento com data-i18n="chave" troca o conteúdo; data-i18n-attr="atributo:chave" troca atributos.
   Ao mudar um texto em português no index.html, ajuste a chave nos dois dicionários. */
(function () {
  'use strict';

  var HTML_LANG = { pt: 'pt-BR', en: 'en', es: 'es' };

  var T = {
    en: {
      title: 'Rumo · home screen for Android car head units',
      desc: 'Rumo is a free launcher for Android car head units: current street and speed limit, speed cameras, radio, music and trip info on the home screen, without opening any app. Navigation stays in Android Auto.',
      ogTitle: 'Rumo · home screen for Android car head units',
      ogDesc: 'Street, speed limit, speed cameras, radio and music on your car head unit’s home screen. Free, offline and open source.',
      'nav.label': 'Sections',
      'lang.label': 'Language',
      'nav.features': 'Features',
      'nav.proto': 'Prototype',
      'nav.install': 'Install',
      'hero.eyebrow': 'Launcher for Android head units',
      'hero.title': 'The home screen your car <em>deserved</em>.',
      'hero.lead': 'Current street, speed limit, speed cameras, radio and music on a single screen, without opening any app. Navigation stays in Android Auto; Rumo takes care of the rest while it’s on screen. Free and offline.',
      'cta.apk': 'Download the APK',
      'cta.proto': 'Try the prototype',
      'cta.code': 'Code on GitHub',
      'hero.small': 'Built and tested on an HCN head unit running Android 9. See <a href="#compat">compatibility</a> before installing on another device.',
      'proto.frame': 'Interactive Rumo prototype',
      'proto.bar': 'Prototype with sample data: tap <b>Dirigir</b> (“Drive”) to ride along the map, switch on <b>Farol aceso</b> (headlights), open <b>Rádio</b> and mark points with the <b>+</b>. The prototype interface is in Portuguese.',
      'proto.full': 'Open full screen ›',
      'f.eyebrow': 'What it does',
      'f.title': 'The essentials, right on the home screen',
      'f.lead': 'The core idea is the <b>zero layer</b>: what matters for driving stays in view, without opening anything.',
      'f1.t': 'Offline map, street and speed limit',
      'f1.p': 'OpenStreetMap map stored on the device, with the current street and the road’s speed limit sign. When OSM has no limit, it uses the default from Brazil’s traffic code (CTB) and flags it as estimated.',
      'f2.t': 'Speed cameras with alerts and beeps',
      'f2.p': 'A short alert when you enter a camera’s zone. Above its limit, beeps that speed up and rise in pitch as you get closer, and stop when you slow down. Distance and margin are adjustable.',
      'f3.t': 'Mark what’s missing',
      'f3.p': 'One tap on the + and one on the type: speed camera, speed bump, red-light camera, highway police or toll. The point works instantly and stays only on your device, even after updating Rumo.',
      'f4.t': 'Radio with favorites and names',
      'f4.p': 'Its own radio screen: big frequency, seek and 0.2 MHz fine tuning, unlimited favorites with the names you choose, and a station scan.',
      'f5.t': 'Media that knows the source',
      'f5.p': 'The card shows whether the sound comes from Android Auto or the radio, even when switched outside Rumo, and controls it with the previous, play and next buttons.',
      'f6.t': 'Trip',
      'f6.p': 'Speed (in red above the limit), heading, distance, moving time and average, all from the device’s GPS.',
      'f7.t': 'Day and night on its own',
      'f7.p': 'The dark theme turns on with the car’s headlights, when the head unit reports them; otherwise it follows the clock. You can also pin dark or light.',
      'f8.t': 'Volume you can set while driving',
      'f8.p': 'Drag a finger on the left menu to the right and a large volume control opens, with repeat when held. It folds away by itself.',
      'f9.t': 'Updates itself',
      'f9.p': 'Every 4 hours Rumo checks for a new version on your channel (stable or nightly) and lights up an icon at the top, with the news in a few lines. It downloads the APK checking its hash, and the maps by state. Only while parked.',
      't.eyebrow': 'How it thinks',
      't.title': 'Everything in its place',
      't.lead': 'Rumo isn’t trying to be a navigation app or a music player. It’s the home screen: it gathers what the car already has and puts it in view.',
      't1': '<b>Navigation stays in Android Auto.</b> Rumo’s map is passive: it shows street and limit. Tapping it opens Android Auto.',
      't2': '<b>Alerts only while Rumo is on screen.</b> Nothing over Android Auto: no bubble, no overlay, no background service.',
      't3': '<b>Offline and free.</b> OpenStreetMap map and limits stored on the device; speed cameras from sources that allow use. Code under GPL 3.0.',
      't4': '<b>Made for Brazil</b>, with default CTB limits and Brazilian speed-camera data.',
      'i.eyebrow': 'Install',
      'i.title': 'In a few minutes',
      'i1': 'On <a href="https://github.com/claytoncarmo/rumo-car-launcher/releases"><b>Releases</b></a>, download <code>rumo.apk</code> from the latest stable version (or from <b>nightly</b>, built on every change and possibly unstable).',
      'i2': 'Copy it to the device (for example on a USB drive), open it and install; allow “install unknown apps” if asked.',
      'i3': 'Press <b>Home</b> and choose Rumo as the home app (“Always”).',
      'i4': 'For map, limits and cameras: gear icon › <b>Configurações da home</b> (while parked) and download your state’s data over Wi-Fi.',
      'c.eyebrow': 'Compatibility',
      'c.title': 'Tested on one device only',
      'c.lead': 'Rumo was born on an HCN head unit (Android 9, 32-bit, 1280×720 screen). What uses only standard Android should work on any Android 8 or later: home screen, apps, offline map, street and limit, speed cameras, Trip and updates.',
      'c.n1': 'These depend on the unit’s manufacturer and may not show up on another device: <b>headlights</b> (automatic theme), <b>audio source</b> and <b>radio</b> (station, names, scan). The diagnostics in settings write a report that helps support more devices.',
      'c.n2': 'Camera alerts are an aid. Obeying road signs and driving with attention is the driver’s responsibility.',
      'c.n3': 'The app and the prototype are in Brazilian Portuguese for now, and the map data is Brazilian (the South and Southeast regions so far).',
      'foot.p1': '<b>Rumo</b> · © 2026 Clayton Carmo. Code under <a href="https://github.com/claytoncarmo/rumo-car-launcher/blob/main/LICENSE">GNU GPL 3.0</a>. Map and roads © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a> (ODbL). Rumo is not affiliated with any head unit manufacturer or with Android Auto; the names mentioned belong to their respective owners.',
      'foot.p2': '<a href="https://github.com/claytoncarmo/rumo-car-launcher">Code</a> · <a href="https://github.com/claytoncarmo/rumo-car-launcher/releases">Releases</a> · <a href="https://github.com/claytoncarmo/rumo-car-launcher/issues">Questions and ideas</a> · <a href="https://github.com/claytoncarmo/rumo-car-launcher/blob/main/TODO.md">What’s next</a>'
    },

    es: {
      title: 'Rumo · pantalla de inicio para multimedia Android del auto',
      desc: 'Rumo es un launcher libre para centrales multimedia Android de auto: calle actual y límite de velocidad, radares, radio, música y viaje en la pantalla de inicio, sin abrir ninguna app. La navegación se queda en Android Auto.',
      ogTitle: 'Rumo · pantalla de inicio para multimedia Android del auto',
      ogDesc: 'Calle, límite de velocidad, radares, radio y música en la pantalla de inicio de la central multimedia de tu auto. Libre, sin conexión y de código abierto.',
      'nav.label': 'Secciones',
      'lang.label': 'Idioma',
      'nav.features': 'Funciones',
      'nav.proto': 'Prototipo',
      'nav.install': 'Instalar',
      'hero.eyebrow': 'Launcher para multimedia Android',
      'hero.title': 'La pantalla de inicio que tu auto <em>merecía</em>.',
      'hero.lead': 'Calle actual, límite de velocidad, radares, radio y música en una sola pantalla, sin abrir ninguna app. La navegación sigue en Android Auto; Rumo se ocupa del resto mientras está en pantalla. Libre y sin conexión.',
      'cta.apk': 'Descargar el APK',
      'cta.proto': 'Probar el prototipo',
      'cta.code': 'Código en GitHub',
      'hero.small': 'Hecho y probado en una central HCN con Android 9. Mira <a href="#compat">la compatibilidad</a> antes de instalar en otro equipo.',
      'proto.frame': 'Prototipo interactivo de Rumo',
      'proto.bar': 'Prototipo con datos de ejemplo: toca <b>Dirigir</b> («Conducir») para recorrer el mapa, activa <b>Farol aceso</b> (faros encendidos), abre <b>Rádio</b> y marca puntos con el <b>+</b>. La interfaz del prototipo está en portugués.',
      'proto.full': 'Abrir en pantalla completa ›',
      'f.eyebrow': 'Qué hace',
      'f.title': 'Lo esencial, ya en la pantalla de inicio',
      'f.lead': 'La idea central es la <b>capa cero</b>: lo que importa para conducir queda a la vista, sin abrir nada.',
      'f1.t': 'Mapa sin conexión, calle y límite',
      'f1.p': 'Mapa de OpenStreetMap guardado en el equipo, con la calle actual y la señal del límite de la vía. Cuando OSM no tiene el límite, usa el valor estándar del CTB (código de tránsito de Brasil) y lo marca como estimado.',
      'f2.t': 'Radares con aviso y pitidos',
      'f2.p': 'Un aviso corto al entrar en la zona del radar. Por encima de su límite, pitidos que se aceleran y suben de tono a medida que te acercas, y se detienen al reducir. Distancia y margen ajustables.',
      'f3.t': 'Marca lo que falta',
      'f3.p': 'Un toque en el + y otro en el tipo: radar, reductor de velocidad, semáforo con cámara, policía de caminos o peaje. El punto vale al instante y queda solo en tu equipo, incluso al actualizar Rumo.',
      'f4.t': 'Radio con favoritas y nombres',
      'f4.p': 'Pantalla de radio propia: frecuencia en letras grandes, búsqueda y ajuste fino de 0,2 MHz, favoritas sin límite con el nombre que quieras y búsqueda de emisoras.',
      'f5.t': 'Medios que conocen la fuente',
      'f5.p': 'La tarjeta muestra si el sonido viene de Android Auto o de la radio, aunque se cambie fuera de Rumo, y lo controla con los botones anterior, reproducir y siguiente.',
      'f6.t': 'Viaje',
      'f6.p': 'Velocidad (en rojo por encima del límite), rumbo, distancia, tiempo en movimiento y promedio, todo con el GPS del equipo.',
      'f7.t': 'Día y noche solo',
      'f7.p': 'El tema oscuro se activa con los faros del auto, cuando la central lo informa; si no, sigue la hora. También puedes fijar oscuro o claro.',
      'f8.t': 'Volumen que se ajusta conduciendo',
      'f8.p': 'Arrastra el dedo en el menú de la izquierda hacia la derecha y se abre un control de volumen grande, con repetición al mantener. Se oculta solo.',
      'f9.t': 'Se actualiza por dentro',
      'f9.p': 'Cada 4 horas Rumo busca una versión nueva en tu canal (estable o nightly) y enciende un icono arriba, con las novedades en pocas líneas. Descarga el APK verificando el hash, y los mapas por estado. Solo con el auto detenido.',
      't.eyebrow': 'Cómo piensa',
      't.title': 'Cada cosa en su lugar',
      't.lead': 'Rumo no intenta ser un navegador ni un reproductor. Es la pantalla de inicio: reúne lo que el auto ya tiene y lo pone a la vista.',
      't1': '<b>La navegación se queda en Android Auto.</b> El mapa de Rumo es pasivo: muestra calle y límite. Tocarlo abre Android Auto.',
      't2': '<b>Avisos solo con Rumo en pantalla.</b> Nada encima de Android Auto: sin globos, sin overlay, sin servicio en segundo plano.',
      't3': '<b>Sin conexión y libre.</b> Mapa y límites de OpenStreetMap guardados en el equipo; radares de fuentes de uso permitido. Código bajo GPL 3.0.',
      't4': '<b>Hecho para Brasil</b>, con los límites estándar del CTB y radares brasileños.',
      'i.eyebrow': 'Instalar',
      'i.title': 'En pocos minutos',
      'i1': 'En <a href="https://github.com/claytoncarmo/rumo-car-launcher/releases"><b>Releases</b></a>, descarga <code>rumo.apk</code> de la última versión estable (o del <b>nightly</b>, compilado en cada cambio y posiblemente inestable).',
      'i2': 'Cópialo al equipo (por ejemplo en una memoria USB), ábrelo e instálalo; permite «instalar apps desconocidas» si lo pide.',
      'i3': 'Pulsa <b>Inicio</b> y elige Rumo como pantalla de inicio («Siempre»).',
      'i4': 'Para mapa, límites y radares: engranaje › <b>Configurações da home</b> (con el auto detenido) y descarga los datos de tu estado por Wi-Fi.',
      'c.eyebrow': 'Compatibilidad',
      'c.title': 'Probado en un solo equipo',
      'c.lead': 'Rumo nació en una central multimedia HCN (Android 9, 32 bits, pantalla de 1280×720). Lo que usa solo Android estándar debería funcionar en cualquier Android 8 o superior: pantalla de inicio, apps, mapa sin conexión, calle y límite, radares, Viaje y actualización.',
      'c.n1': 'Dependen del fabricante de la central y pueden no aparecer en otro equipo: <b>faros</b> (tema automático), <b>fuente de audio</b> y <b>radio</b> (emisora, nombres, búsqueda). Los diagnósticos de los ajustes generan un informe que ayuda a dar soporte a más equipos.',
      'c.n2': 'Los avisos de radar son una ayuda. Respetar la señalización y conducir con atención es responsabilidad de quien conduce.',
      'c.n3': 'La app y el prototipo están por ahora en portugués de Brasil, y los datos del mapa son de Brasil (por ahora, las regiones Sur y Sudeste).',
      'foot.p1': '<b>Rumo</b> · © 2026 Clayton Carmo. Código bajo <a href="https://github.com/claytoncarmo/rumo-car-launcher/blob/main/LICENSE">GNU GPL 3.0</a>. Mapa y vías © <a href="https://www.openstreetmap.org/copyright">colaboradores de OpenStreetMap</a> (ODbL). Rumo no está afiliado a ningún fabricante de centrales multimedia ni a Android Auto; los nombres citados pertenecen a sus respectivos dueños.',
      'foot.p2': '<a href="https://github.com/claytoncarmo/rumo-car-launcher">Código</a> · <a href="https://github.com/claytoncarmo/rumo-car-launcher/releases">Versiones</a> · <a href="https://github.com/claytoncarmo/rumo-car-launcher/issues">Dudas e ideas</a> · <a href="https://github.com/claytoncarmo/rumo-car-launcher/blob/main/TODO.md">Qué viene</a>'
    }
  };

  var root = document.documentElement;
  var textNodes = Array.prototype.slice.call(document.querySelectorAll('[data-i18n]'));
  var attrNodes = Array.prototype.slice.call(document.querySelectorAll('[data-i18n-attr]'));
  var metas = [
    { key: 'desc', el: document.getElementById('m-desc'), attr: 'content' },
    { key: 'ogTitle', el: document.getElementById('m-ogt'), attr: 'content' },
    { key: 'ogDesc', el: document.getElementById('m-ogd'), attr: 'content' }
  ];

  /* O que está no HTML é o português: guarda para poder voltar */
  var PT = { title: document.title, nodes: [], attrs: [], metas: [] };
  textNodes.forEach(function (el) { PT.nodes.push(el.innerHTML); });
  attrNodes.forEach(function (el) {
    PT.attrs.push(el.getAttribute('data-i18n-attr').split(';').map(function (p) {
      var a = p.split(':')[0].trim();
      return { name: a, value: el.getAttribute(a) };
    }));
  });
  metas.forEach(function (m) { PT.metas.push(m.el ? m.el.getAttribute(m.attr) : null); });

  function apply(lang) {
    var d = T[lang];
    textNodes.forEach(function (el, i) {
      var v = d && d[el.getAttribute('data-i18n')];
      el.innerHTML = v != null ? v : PT.nodes[i];
    });
    attrNodes.forEach(function (el, i) {
      el.getAttribute('data-i18n-attr').split(';').forEach(function (p, j) {
        var parts = p.split(':'), v = d && d[parts[1].trim()];
        el.setAttribute(parts[0].trim(), v != null ? v : PT.attrs[i][j].value);
      });
    });
    document.title = (d && d.title) || PT.title;
    metas.forEach(function (m, i) {
      if (m.el) m.el.setAttribute(m.attr, (d && d[m.key]) || PT.metas[i]);
    });
    root.setAttribute('lang', HTML_LANG[lang]);
    Array.prototype.forEach.call(document.querySelectorAll('#lang button'), function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-lang') === lang));
    });
    root.classList.remove('i18n-wait');
  }

  apply(window.__lang || 'pt');

  var box = document.getElementById('lang');
  if (box) {
    box.addEventListener('click', function (e) {
      var b = e.target.closest ? e.target.closest('button[data-lang]') : null;
      if (!b) return;
      var lang = b.getAttribute('data-lang');
      try { localStorage.setItem('rumo.lang', lang); } catch (err) { /* armazenamento indisponível */ }
      // A escolha manda mais que o ?lang= do endereço
      try {
        var u = new URL(location.href);
        if (u.searchParams.has('lang')) { u.searchParams.delete('lang'); history.replaceState(null, '', u.pathname + u.search + u.hash); }
      } catch (err) { /* sem URL() */ }
      apply(lang);
    });
  }
})();
