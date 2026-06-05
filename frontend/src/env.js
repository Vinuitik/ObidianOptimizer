const ENV = {
  API_BASE: '/api',

  PORTS: {
    FRONTEND: 8083,
    BACKEND: 8084,
    DEV: 5173,
  },

  FEATURES: {
    REVIEW_PANEL: true,
    TRASH_RESTORE: false,
    CROSS_FILE_RENAME: false,
  },

  LIMITS: {
    ITEMS_PER_PAGE: 25,
  },
};

export default ENV;
