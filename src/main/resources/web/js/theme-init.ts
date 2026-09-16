(function () {
   try {
      fetch('/api/store?key=graffiti:theme')
         .then(function (r) { return r.json(); })
         .then(function (d) {
            if (d && d.value) {
               let theme = d.value;
               if (theme === 'dark') theme = 'dark-sage';
               if (theme === 'light') theme = 'light-purple';
               document.documentElement.setAttribute('data-theme', theme);
               const metaTheme = document.querySelector('meta[name="theme-color"]');
               if (metaTheme) {
                  metaTheme.setAttribute('content', theme.startsWith('light') ? '#ffffff' : '#000000');
               }
            }
         })
         .catch(function () {});
   } catch (_) {}
})();
