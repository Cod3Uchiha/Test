### Code Files
---
**index.html**
```html
<!DOCTYPE html>
<html lang="en">

<head>
  <meta charset="UTF-8" />
  <title>Admin Dashboard</title>
  <link rel="stylesheet" href="style.css" />
  <script src="script.js"></script>
</head>

<body>
  <nav class="nav">
    <div class="nav-logo">
      <img src="logo.png" alt="Logo" />
      <span>Admin Dashboard</span>
    </div>
    <ul class="nav-links">
      <li><a href="#">Dashboard</a></li>
      <li><a href="#">Users</a></li>
      <li><a href="#">Settings</a></li>
    </ul>
  </nav>

  <main class="main">
    <div class="main-header">
      <h1>Dashboard</h1>
      <div class="main-header-actions">
        <button class="btn">Refresh</button>
        <button class="btn">Export</button>
      </div>
    </div>

    <div class="main-widgets">
      <div class="widget">
        <div class="widget-header">
          <h2>Users</h2>
          <span class="widget-header-icon"><i class="fas fa-users"></i></span>
        </div>
        <div class="widget-content">
          <ul>
            <li>Total users: 100</li>
            <li>Active users: 75</li>
            <li>Inactive users: 25</li>
          </ul>
        </div>
      </div>

      <div class="widget">
        <div class="widget-header">
          <h2>Orders</h2>
          <span class="widget-header-icon"><i class="fas fa-shopping-cart"></i></span>
        </div>
        <div class="widget-content">
          <ul>
            <li>Total orders: 200</li>
            <li>Pending orders: 50</li>
            <li>Fulfilled orders: 150</li>
          </ul>
        </div>
      </div>

      <div class="widget">
        <div class="widget-header">
          <h2>Revenue</h2>
          <span class="widget-header-icon"><i class="fas fa-dollar-sign"></i></span>
        </div>
        <div class="widget-content">
          <p>Total revenue: $10,000</p>
          <p>Monthly revenue: $2,500</p>
          <p>Yearly revenue: $30,000</p>
        </div>
      </div>
    </div>

    <div class="main-charts">
      <div class="chart">
        <canvas id="myChart"></canvas>
      </div>
      <div class="chart">
        <canvas id="myChart2"></canvas>
      </div>
    </div>
  </main>

  <footer class="footer">
    <p>Copyright &copy; 2023 Admin Dashboard</p>
  </footer>
</body>

</html>
```

**style.css**
```css
*,
*::before,
*::after {
  box-sizing: border-box;
}

body {
  font-family: Arial, sans-serif;
  margin: 0;
  padding: 0;
}

.nav {
  background-color: #222;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
}

.nav-logo {
  display: flex;
  align-items: center;
}

.nav-logo img {
  width: 30px;
  height: 30px;
  margin-right: 10px;
}

.nav-links {
  display: flex;
  list-style: none;
}

.nav-links li {
  margin-right: 10px;
}

.nav-links a {
  color: #fff;
  text-decoration: none;
}

.main {
  padding: 10px;
}

.main-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.main-header h1 {
  margin-bottom: 0;
}

.main-header-actions {
  display: flex;
  gap: 10px;
}

.btn {
  padding: 5px 10px;
  border: 1px solid #ccc;
  border-radius: 5px;
  background-color: #fff;
  color: #222;
  text-decoration: none;
  cursor: pointer;
}

.btn:hover {
  background-color: #eee;
}

.main-widgets {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.widget {
  width: 200px;
  padding: 10px;
  border: 1px solid #ccc;
  border-radius: 5px;
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: #eee;
  padding: 5px;
}

.widget-header h2 {
  margin-bottom: 0;
}

.widget-header-icon {
  font-size: 1.5rem;
  color: #222;
}

.widget-content {
  padding: 10px;
}

.main-charts {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.chart {
  width: 300px;
  height: 200px;
  background-color: #eee;
}

.footer {
  background-color: #222;
  color: #fff;
  padding: 10px;
  text-align: center;
}
```

**script.js**
```javascript
// Get the canvas elements
const ctx = document.getElementById('myChart').getContext('2d');
const ctx2 = document.getElementById('myChart2').getContext('2d');

// Create the charts
const myChart = new Chart(ctx, {
  type: 'bar',
  data: {
    labels: ['Red', 'Blue', 'Yellow', 'Green', 'Purple'],
    datasets: [{
      label: '# of Votes',
      data: [12, 19, 3, 5, 2],
      backgroundColor: [
        'rgba(255, 99, 132, 0.2)',
        'rgba(54, 162, 235, 0.2)',
        'rgba(255, 206, 86, 0.2)',
        'rgba(75, 192, 192, 0.2)',
        'rgba(153, 102, 255, 0.2)'
      ],
      borderColor: [
        'rgba(255, 99, 132, 1)',
        'rgba(54, 162, 235, 1)',
        'rgba(255, 206, 86, 1)',
        'rgba(75, 192, 192, 1)',
        'rgba(153, 102, 255, 1)'
      ],
      borderWidth: 1
    }]
  },
  options: {
    scales: {
      y: {
        beginAtZero: true
      }
    }
  }
});

const myChart2 = new Chart(ctx2, {
  type: 'line',
  data: {
    labels: ['January', 'February', 'March', 'April', 'May', 'June', 'July'],
    datasets: [{
      label: 'My First Dataset',
      data: [65, 59, 80, 81, 56, 55, 40],
      backgroundColor: [
        'rgba(255, 99, 132, 0.2)'
