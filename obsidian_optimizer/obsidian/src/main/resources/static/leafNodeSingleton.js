// leafNodeSingleton.js
export class LeafNodeSingleton {
    static instance = null;
    leafMap = new Map();
  
    static getInstance() {
      if (LeafNodeSingleton.instance === null) {
        LeafNodeSingleton.instance = new LeafNodeSingleton();
      }
      return LeafNodeSingleton.instance;
    }
  
    set(shortName, fullPath) {
      this.leafMap.set(shortName, fullPath);
    }
  
    has(shortName) {
      return this.leafMap.has(shortName);
    }
  
    forEach(callback) {
      this.leafMap.forEach(callback);
    }
  }
  