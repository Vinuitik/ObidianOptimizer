import { Component } from 'react';
import styles from './EditorErrorBoundary.module.css';

export default class EditorErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('[EditorErrorBoundary] Milkdown render error:', error);
    console.error('[EditorErrorBoundary] Component stack:', info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div className={styles.errorBox}>
          <div className={styles.errorTitle}>Editor failed to render</div>
          <pre className={styles.errorMsg}>{this.state.error.message}</pre>
          <pre className={styles.errorStack}>{this.state.error.stack}</pre>
        </div>
      );
    }
    return this.props.children;
  }
}
