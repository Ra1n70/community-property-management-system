import React from 'react';
export default class PageErrorBoundary extends React.Component {
  state = {failed: false};
  static getDerivedStateFromError() { return {failed: true}; }
  render() {
    if (this.state.failed) return <main><section className="card"><h1>Unable to load this page</h1><p role="alert">Please reload the page to try again.</p><button onClick={() => location.reload()}>Reload page</button></section></main>;
    return this.props.children;
  }
}
