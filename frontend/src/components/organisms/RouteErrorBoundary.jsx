import { Component } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import styles from './RouteErrorBoundary.module.css';

// App-level boundary. Without one, a throw in ANY routed leaf (e.g. Learn) unmounts the
// whole React root — in the installed PWA that blanks the screen AND kills the bottom nav,
// so the app becomes unusable. This catches the throw, keeps the surrounding shell/nav
// mounted, and shows the error text on-screen (so a phone-only crash is diagnosable).
class Boundary extends Component {
  constructor(props) { super(props); this.state = { error: null }; }
  static getDerivedStateFromError(error) { return { error }; }
  componentDidCatch(error, info) {
    console.error('[RouteErrorBoundary]', error);
    console.error('[RouteErrorBoundary] component stack:', info?.componentStack);
  }
  render() {
    if (this.state.error) {
      return (
        <div className={styles.wrap} role="alert">
          <div className={styles.title}>This screen hit an error</div>
          <p className={styles.hint}>
            The rest of the app still works — switch tabs below, or reload.
          </p>
          <pre className={styles.msg}>{this.state.error.message}</pre>
          <div className={styles.actions}>
            {/* Real escape, not just a reload-into-the-same-crash: go back to the review
                list (fresh route → boundary remounts clean). */}
            <button className={styles.reload} onClick={() => this.props.onLeave?.()}>
              Back to Review
            </button>
            <button className={styles.ghost} onClick={() => window.location.reload()}>
              Reload
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

// Reset on route change: a class boundary latches its error until remounted, so keying it
// by pathname lets switching tabs recover without a full reload (the new page mounts fresh).
// onLeave navigates to a known-good route — changing the key remounts a clean subtree.
export default function RouteErrorBoundary({ children }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  return <Boundary key={pathname} onLeave={() => navigate('/review')}>{children}</Boundary>;
}
