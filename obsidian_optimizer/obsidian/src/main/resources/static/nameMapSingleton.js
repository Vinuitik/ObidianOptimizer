export const NameMapSingleton = (function () {
    let instance;
  
    function createInstance() {
      return new Map();
    }
  
    return {
      getInstance: function () {
        if (!instance) {
          instance = createInstance();
        }
        return instance;
      }
    };
  })();
  